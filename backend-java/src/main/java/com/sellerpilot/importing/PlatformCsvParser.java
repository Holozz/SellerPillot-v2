package com.sellerpilot.importing;

import com.sellerpilot.common.ApiException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Component;

@Component
public class PlatformCsvParser {
    private final tools.jackson.databind.json.JsonMapper json;
    public PlatformCsvParser(tools.jackson.databind.json.JsonMapper json){this.json=json;}
    public record Row(int number,String key,String hash,Map<String,String> raw,Map<String,Object> normalized){}
    public record Parsed(String kind,String platform,String storeKey,String hash,byte[] file,List<Row> rows){}
    private static final Map<String,Map<String,List<String>>> HEADERS=Map.of(
        "market-a",Map.of(
            "orders",List.of("platform_code","store_key","external_order_id","ordered_at","paid_at","status","currency"),
            "order-items",List.of("platform_code","store_key","external_line_id","external_order_id","external_sku","quantity","unit_price","discount_amount","currency","source_batch_id"),
            "refunds",List.of("platform_code","store_key","external_refund_id","external_line_id","confirmed_at","refund_amount","currency","returned_quantity","restocked"),
            "procurement-batches",List.of("platform_code","store_key","source_batch_id","external_sku","received_at","quantity","unit_purchase_cost","currency")),
        "market-b",Map.of(
            "orders",List.of("platform_code","store_key","external_order_id","order_timestamp","payment_timestamp","order_state","order_currency"),
            "order-items",List.of("platform_code","store_key","line_key","order_number","merchant_sku","units","unit_sale_price","line_discount","price_currency","purchase_lot"),
            "refunds",List.of("platform_code","store_key","refund_id","line_key","confirmation_timestamp","refund_value","refund_currency","returned_units","restocked"),
            "procurement-batches",List.of("platform_code","store_key","purchase_lot","merchant_sku","receipt_timestamp","received_units","cost_per_unit","cost_currency")));

    public Parsed parse(String kind,String expectedPlatform,String expectedStoreKey,byte[] bytes){
        if(bytes.length==0||bytes.length>5*1024*1024)throw new ApiException(422,"CSV大小须在1字节至5MiB之间");
        Map<String,List<String>> platformHeaders=HEADERS.get(expectedPlatform);
        if(platformHeaders==null||!platformHeaders.containsKey(kind))throw new ApiException(422,"平台或导入类型不支持");
        String content;
        try{content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException e){throw new ApiException(422,"CSV须使用UTF-8编码");}
        if(content.startsWith("\uFEFF"))content=content.substring(1);
        List<Row> rows=new ArrayList<>();Set<String> seen=new HashSet<>();
        try(CSVParser csv=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).get().parse(new StringReader(content))){
            List<String> extended=new ArrayList<>(platformHeaders.get(kind));
            if(kind.equals("order-items"))extended.set(extended.size()-1,"batch_allocations");
            if(kind.equals("refunds"))extended.add("return_allocations");
            if(!csv.getHeaderNames().equals(platformHeaders.get(kind))&&!csv.getHeaderNames().equals(extended))throw new ApiException(422,"CSV表头或字段顺序不正确");
            String storeKey=null;int count=0;
            for(CSVRecord record:csv){
                int number=(int)csv.getCurrentLineNumber();if(++count>10000)throw new ApiException(422,"每个文件最多10000条记录");
                if(!record.isConsistent())throw new ApiException(422,"第"+number+"行字段数量不正确");
                Map<String,String> raw=new LinkedHashMap<>();for(String header:csv.getHeaderNames())raw.put(header,record.get(header).trim());
                if(!expectedPlatform.equals(raw.get("platform_code")))throw new ApiException(422,"第"+number+"行平台标识与店铺不匹配");
                String rowStore=required(raw,"store_key",128);if(!rowStore.equals(expectedStoreKey))throw new ApiException(422,"来源店铺标识与目标店铺不匹配");if(storeKey==null)storeKey=rowStore;else if(!storeKey.equals(rowStore))throw new ApiException(422,"一个文件只能包含一家来源店铺");
                Map<String,Object> normalized=normalize(kind,expectedPlatform,raw);
                String key=key(kind,normalized);if(key!=null&&!seen.add(key))throw new ApiException(422,"文件内业务键重复："+key);
                rows.add(new Row(number,key,digest(String.join("\u001f",raw.values()).getBytes(StandardCharsets.UTF_8)),raw,normalized));
            }
            if(rows.isEmpty()&&kind.equals("refunds")){
                // A header-only refund export is valid and means that the period has no refunds.
                storeKey=expectedStoreKey;
            }else if(rows.isEmpty())throw new ApiException(422,"CSV没有数据记录");
            return new Parsed(kind,expectedPlatform,storeKey,digest(bytes),bytes.clone(),List.copyOf(rows));
        }catch(ApiException e){throw e;}catch(Exception e){throw new ApiException(422,"CSV引号或文件格式无效");}
    }
    private Map<String,Object> normalize(String kind,String platform,Map<String,String> r){
        Map<String,Object> n=new LinkedHashMap<>();
        switch(kind){
            case "orders"->{
                n.put("external_order_id",required(r,platform.equals("market-a")?"external_order_id":"external_order_id",128));
                n.put("ordered_at",instant(r.get(platform.equals("market-a")?"ordered_at":"order_timestamp")));
                n.put("paid_at",instant(r.get(platform.equals("market-a")?"paid_at":"payment_timestamp")));
                String status=required(r,platform.equals("market-a")?"status":"order_state",32).toLowerCase(Locale.ROOT);if(status.equals("settled"))status="paid";
                if(!Set.of("pending","paid","shipped","cancelled").contains(status))throw new ApiException(422,"订单状态无效");n.put("status",status);
                n.put("currency",currency(r.get(platform.equals("market-a")?"currency":"order_currency")));
            }
            case "order-items"->{
                String line=required(r,platform.equals("market-a")?"external_line_id":"line_key",128);
                n.put("external_line_id",line);n.put("external_order_id",required(r,platform.equals("market-a")?"external_order_id":"order_number",128));
                n.put("external_sku",required(r,platform.equals("market-a")?"external_sku":"merchant_sku",128));
                BigDecimal quantity=positive(r.get(platform.equals("market-a")?"quantity":"units"));
                BigDecimal unitPrice=money(r.get(platform.equals("market-a")?"unit_price":"unit_sale_price"));
                BigDecimal discount=money(r.get(platform.equals("market-a")?"discount_amount":"line_discount"));
                if(discount.compareTo(quantity.multiply(unitPrice))>0)throw new ApiException(422,"明细折扣不能超过商品金额");
                n.put("quantity",quantity);n.put("unit_price",unitPrice);n.put("discount_amount",discount);
                n.put("currency",currency(r.get(platform.equals("market-a")?"currency":"price_currency")));
                List<Map<String,Object>> allocations=r.containsKey("batch_allocations")?allocations(r.get("batch_allocations"),quantity):List.of(Map.of("source_batch_id",required(r,platform.equals("market-a")?"source_batch_id":"purchase_lot",128),"quantity",quantity));
                n.put("batch_allocations",allocations);n.put("source_batch_id",allocations.getFirst().get("source_batch_id"));
            }
            case "refunds"->{
                n.put("external_refund_id",required(r,platform.equals("market-a")?"external_refund_id":"refund_id",128));
                n.put("external_line_id",required(r,platform.equals("market-a")?"external_line_id":"line_key",128));
                n.put("confirmed_at",instant(r.get(platform.equals("market-a")?"confirmed_at":"confirmation_timestamp")));
                n.put("refund_amount",money(r.get(platform.equals("market-a")?"refund_amount":"refund_value")));
                n.put("currency",currency(r.get(platform.equals("market-a")?"currency":"refund_currency")));
                n.put("returned_quantity",nonnegative(r.get(platform.equals("market-a")?"returned_quantity":"returned_units")));
                String restocked=required(r,"restocked",5).toLowerCase(Locale.ROOT);if(!Set.of("true","false").contains(restocked))throw new ApiException(422,"restocked须为true或false");n.put("restocked",Boolean.valueOf(restocked));
                if(r.containsKey("return_allocations"))n.put("return_allocations",allocations(r.get("return_allocations"),decimal(String.valueOf(n.get("returned_quantity")))));
            }
            case "procurement-batches"->{
                n.put("source_batch_id",required(r,platform.equals("market-a")?"source_batch_id":"purchase_lot",128));
                n.put("external_sku",required(r,platform.equals("market-a")?"external_sku":"merchant_sku",128));
                n.put("received_at",instant(r.get(platform.equals("market-a")?"received_at":"receipt_timestamp")));
                n.put("quantity",positive(r.get(platform.equals("market-a")?"quantity":"received_units")));
                n.put("unit_purchase_cost",money(r.get(platform.equals("market-a")?"unit_purchase_cost":"cost_per_unit")));
                n.put("currency",currency(r.get(platform.equals("market-a")?"currency":"cost_currency")));
            }
            default->throw new ApiException(422,"导入类型不支持");
        }
        return n;
    }
    private static String key(String kind,Map<String,Object> n){return switch(kind){case "orders"->(String)n.get("external_order_id");case "order-items"->(String)n.get("external_line_id");case "refunds"->(String)n.get("external_refund_id");case "procurement-batches"->(String)n.get("source_batch_id");default->null;};}
    private List<Map<String,Object>> allocations(String value,BigDecimal total){
        try{
            List<?> input=json.readValue(value,List.class);if(input.size()>100)throw new ApiException(422,"每行最多100个批次分配");
            List<Map<String,Object>> out=new ArrayList<>();Set<String> seen=new HashSet<>();BigDecimal sum=BigDecimal.ZERO;
            for(Object item:input){Map<?,?> a=(Map<?,?>)item;if(!a.keySet().equals(Set.of("source_batch_id","quantity")))throw new ApiException(422,"批次分配须仅含source_batch_id和quantity");String batch=String.valueOf(a.get("source_batch_id"));if(batch.isBlank()||batch.length()>128||!seen.add(batch))throw new ApiException(422,"批次编号为空、过长或重复");BigDecimal qty=positive(String.valueOf(a.get("quantity")));sum=sum.add(qty);out.add(Map.of("source_batch_id",batch,"quantity",qty));}
            if(sum.compareTo(total)!=0)throw new ApiException(422,"批次分配数量之和必须等于销售或退货数量");return out;
        }catch(ApiException e){throw e;}catch(Exception e){throw new ApiException(422,"批次分配须为JSON数组");}
    }
    private static String required(Map<String,String> r,String field,int max){String value=r.getOrDefault(field,"").trim();if(value.isEmpty()||value.length()>max)throw new ApiException(422,"字段"+field+"不能为空且长度不能超过"+max);return value;}
    private static String currency(String value){String v=value==null?"":value.trim();if(!v.matches("[A-Z]{3}"))throw new ApiException(422,"币种须为三位大写代码");return v;}
    private static BigDecimal money(String value){try{BigDecimal n=new BigDecimal(value.trim());if(n.signum()<0||n.scale()>2||n.precision()-n.scale()>16)throw new NumberFormatException();return n;}catch(Exception e){throw new ApiException(422,"金额须为非负且最多两位小数");}}
    private static BigDecimal positive(String value){BigDecimal n=decimal(value);if(n.signum()<=0)throw new ApiException(422,"数量须大于零");return n;}
    private static BigDecimal nonnegative(String value){BigDecimal n=decimal(value);if(n.signum()<0)throw new ApiException(422,"数量不能为负");return n;}
    private static BigDecimal decimal(String value){try{BigDecimal n=new BigDecimal(value.trim());if(n.scale()>6||n.precision()>18)throw new NumberFormatException();return n;}catch(Exception e){throw new ApiException(422,"数量格式无效");}}
    private static Instant instant(String value){try{return OffsetDateTime.parse(value.trim()).toInstant();}catch(Exception e){throw new ApiException(422,"时间须为带时区的ISO-8601时间");}}
    private static String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
}
