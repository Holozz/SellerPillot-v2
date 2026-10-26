package com.sellerpilot.importing;

import com.sellerpilot.common.ApiException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Component;

@Component
public class CsvParser {
    public record Row(int row,String external,LocalDateTime orderedAt,String status,String currency,
                      int line,String sku,int quantity,BigDecimal price,BigDecimal refund){}
    public record Parsed(String kind,List<Row> rows,String hash){}
    private static final List<String> ORDERS=List.of("external_order_id","ordered_at","status","currency");
    private static final List<String> ITEMS=List.of("external_order_id","line_no","sku","quantity","unit_price","refund_amount");
    static ApiException error(List<Map<String,Object>> errors,int total){return new ApiException(422,Map.of(
        "message","CSV校验失败，未写入任何记录","errors",errors,"total_errors",total,"truncated",total>errors.size()));}
    public Parsed parse(String kind,byte[] data){
        if(!Set.of("orders","order-items").contains(kind))throw new ApiException(404,"导入类型不存在");
        if(data.length==0||data.length>5*1024*1024)throw new ApiException(422,"CSV大小须在1字节至5MiB之间");
        String content;
        try{content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();}
        catch(CharacterCodingException e){throw new ApiException(422,"CSV须使用UTF-8编码");}
        if(content.startsWith("\uFEFF"))content=content.substring(1);
        List<Map<String,Object>> errors=new ArrayList<>();List<Row> rows=new ArrayList<>();Set<String> seen=new HashSet<>();int total=0;
        CSVFormat format=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(false)
            .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).get();
        try(CSVParser parser=format.parse(new StringReader(content))){
            if(!parser.getHeaderNames().equals(kind.equals("orders")?ORDERS:ITEMS))throw new ApiException(422,"CSV表头或字段顺序不正确");
            for(CSVRecord record:parser){
                int row=(int)parser.getCurrentLineNumber();
                if(record.getRecordNumber()>10000)throw new ApiException(422,"每个文件最多10000条记录");
                try{
                    if(!record.isConsistent())throw new IllegalArgumentException("字段数量不正确");
                    String external=record.get("external_order_id").trim();if(external.isEmpty()||external.length()>100)throw new IllegalArgumentException("订单号须在1–100字符内");
                    Row result;
                    if(kind.equals("orders")){
                        LocalDateTime at=LocalDateTime.ofInstant(OffsetDateTime.parse(record.get("ordered_at").trim()).toInstant(),ZoneOffset.UTC);
                        if(at.getYear()<1000||at.getYear()>9999)throw new IllegalArgumentException("日期超出MySQL可存储范围");
                        String status=record.get("status").trim(),currency=record.get("currency").trim();
                        if(!Set.of("pending","paid","shipped","cancelled").contains(status)||!currency.matches("[A-Z]{3}"))throw new IllegalArgumentException("订单状态或币种无效");
                        result=new Row(row,external,at,status,currency,0,null,0,null,null);
                    }else{
                        int line=Integer.parseInt(record.get("line_no")),qty=Integer.parseInt(record.get("quantity"));
                        String sku=record.get("sku").trim();BigDecimal price=money(record.get("unit_price")),refund=money(record.get("refund_amount"));
                        if(line<1||qty<1||!sku.matches("[A-Za-z0-9-]{3,30}")||refund.compareTo(price.multiply(BigDecimal.valueOf(qty)))>0)throw new IllegalArgumentException("行号、数量、SKU或退款金额无效");
                        result=new Row(row,external,null,null,null,line,sku,qty,price,refund);
                    }
                    String key=external+(kind.equals("orders")?"":"#"+result.line);
                    if(!seen.add(key))throw new IllegalArgumentException("文件内业务键重复");
                    rows.add(result);
                }catch(IllegalArgumentException|DateTimeException e){
                    total++;if(errors.size()<50)errors.add(Map.of("row",row,"field","record","reason",e.getMessage()==null?"字段无效":e.getMessage()));
                }
            }
        }catch(ApiException e){throw e;}catch(Exception e){throw new ApiException(422,"CSV引号或文件格式无效");}
        if(total>0)throw error(errors,total);
        if(rows.isEmpty())throw new ApiException(422,"文件没有数据记录");
        try{return new Parsed(kind,List.copyOf(rows),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private BigDecimal money(String text){
        BigDecimal n=new BigDecimal(text.trim());if(n.signum()<0||n.scale()>2||n.precision()-n.scale()>16||n.scale()<0)throw new IllegalArgumentException("金额须为非负、最多16位整数和两位小数的定点数");return n;
    }
}
