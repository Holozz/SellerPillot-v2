package com.sellerpilot.importing;

import com.sellerpilot.common.ApiException;
import com.sellerpilot.common.Rows;
import com.sellerpilot.catalog.CatalogMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class CanonicalFactsService {
    private final CommerceFactsMapper facts;private final CatalogMapper catalog;
    public CanonicalFactsService(CommerceFactsMapper facts,CatalogMapper catalog){this.facts=facts;this.catalog=catalog;}
    public Map<Integer,Long> apply(long sid,long owner,long user,String kind,List<PlatformCsvParser.Row> sourceRows,List<Map<String,Object>> storedRows,boolean confirmConflicts){
        Map<Integer,Long> ids=storedRows.stream().collect(Collectors.toMap(r->((Number)r.get("source_row_number")).intValue(),r->((Number)r.get("id")).longValue()));
        return switch(kind){
            case "orders"->orders(sid,user,sourceRows,ids,confirmConflicts);
            case "procurement-batches"->batches(sid,owner,user,sourceRows,ids,confirmConflicts);
            case "order-items"->lines(sid,owner,user,sourceRows,ids,confirmConflicts);
            case "refunds"->refunds(sid,user,sourceRows,ids,confirmConflicts);
            default->throw new ApiException(422,"导入类型不支持");
        };
    }
    private Map<Integer,Long> orders(long sid,long user,List<PlatformCsvParser.Row> rows,Map<Integer,Long> ids,boolean confirm){
        if(rows.isEmpty())return Map.of();
        List<String> keys=rows.stream().map(r->str(r,"external_order_id")).distinct().toList();
        Map<String,Map<String,Object>> current=index(facts.latestOrders(sid,keys),"external_order_id");
        for(PlatformCsvParser.Row row:rows){Map<String,Object> old=current.get(str(row,"external_order_id"));if(old!=null&&!sameOrder(old,row.normalized())&&!confirm)conflict("订单",row.key());}
        Map<Integer,Long> out=new HashMap<>();
        for(PlatformCsvParser.Row row:rows){
            String key=str(row,"external_order_id");Map<String,Object> old=current.get(key);if(old!=null&&sameOrder(old,row.normalized()))continue;
            long revision=old==null?1:Rows.number(old,"revision")+1;
            facts.insertOrder(sid,key,revision,id(ids,row.number()),utc(row.normalized().get("ordered_at")),utc(row.normalized().get("paid_at")),str(row,"status"),str(row,"currency"),user);
        }
        return out;
    }
    private Map<Integer,Long> batches(long sid,long owner,long user,List<PlatformCsvParser.Row> rows,Map<Integer,Long> ids,boolean confirm){
        if(rows.isEmpty())return Map.of();
        List<String> keys=rows.stream().map(r->str(r,"source_batch_id")).distinct().toList();
        Map<String,Map<String,Object>> current=index(facts.latestBatches(sid,keys),"source_batch_id");
        Map<String,BigDecimal> allocated=new HashMap<>();
        for(Map<String,Object> row:facts.allocatedForBatches(sid,keys))allocated.put(String.valueOf(row.get("source_batch_id")),decimal(row.get("allocated_quantity")));
        Map<String,Map<String,Object>> mappings=index(catalog.latestMappings(sid,rows.stream().map(r->str(r,"external_sku")).distinct().toList()),"source_sku");
        for(PlatformCsvParser.Row row:rows)if(!mappings.containsKey(str(row,"external_sku")))throw new ApiException(422,"SKU尚未映射到统一商品："+str(row,"external_sku"));
        for(PlatformCsvParser.Row row:rows){Map<String,Object> old=current.get(str(row,"source_batch_id"));if(old!=null&&!sameBatch(old,row.normalized(),mappings.get(str(row,"external_sku")))&&!confirm)conflict("采购批次",row.key());}
        for(PlatformCsvParser.Row row:rows){
            String batchKey=str(row,"source_batch_id"),sku=str(row,"external_sku");Map<String,Object> old=current.get(batchKey),mapping=mappings.get(sku);
            if(old!=null&&sameBatch(old,row.normalized(),mapping))continue;
            BigDecimal quantity=decimal(row.normalized().get("quantity"));BigDecimal consumed=allocated.getOrDefault(batchKey,BigDecimal.ZERO);
            if(quantity.compareTo(consumed)<0)throw new ApiException(409,"采购批次数量小于已分配数量："+batchKey);
            facts.insertBatch(owner,sid,batchKey,old==null?1:Rows.number(old,"batch_revision")+1,sku,Rows.number(mapping,"mapping_version"),utc(row.normalized().get("received_at")),quantity,decimal(row.normalized().get("unit_purchase_cost")),str(row,"currency"),id(ids,row.number()));
        }
        return Map.of();
    }
    private Map<Integer,Long> lines(long sid,long owner,long user,List<PlatformCsvParser.Row> rows,Map<Integer,Long> ids,boolean confirm){
        if(rows.isEmpty())return Map.of();
        List<String> keys=rows.stream().map(r->str(r,"external_line_id")).toList();
        Map<String,Map<String,Object>> oldLines=index(facts.latestLines(sid,keys),"external_line_id");
        Map<String,Map<String,Object>> orders=index(facts.latestOrders(sid,rows.stream().map(r->str(r,"external_order_id")).distinct().toList()),"external_order_id");
        List<String> batchKeys=rows.stream().flatMap(r->parts(r,"batch_allocations").stream()).map(a->String.valueOf(a.get("source_batch_id"))).distinct().toList();
        Map<String,Map<String,Object>> batches=index(facts.latestBatches(sid,batchKeys),"source_batch_id"),mappings=index(catalog.latestMappings(sid,rows.stream().map(r->str(r,"external_sku")).distinct().toList()),"source_sku");
        Map<Long,List<Map<String,Object>>> oldParts=new HashMap<>();
        if(!oldLines.isEmpty())for(Map<String,Object> a:facts.allocations(sid,oldLines.values().stream().map(r->Rows.number(r,"source_row_id")).toList()))oldParts.computeIfAbsent(Rows.number(a,"source_row_id"),k->new ArrayList<>()).add(a);
        Map<String,BigDecimal> used=new HashMap<>();for(Map<String,Object> a:facts.allocatedForBatches(sid,batchKeys))used.put(String.valueOf(a.get("source_batch_id")),decimal(a.get("allocated_quantity")));
        Map<String,BigDecimal[]> returned=new HashMap<>();for(Map<String,Object> r:facts.currentRefundsForLines(sid,keys)){BigDecimal[] t=returned.computeIfAbsent(String.valueOf(r.get("external_line_id")),k->new BigDecimal[]{BigDecimal.ZERO,BigDecimal.ZERO});t[0]=t[0].add(decimal(r.get("refund_amount")));t[1]=t[1].add(decimal(r.get("returned_quantity")));}
        for(PlatformCsvParser.Row row:rows){
            String line=str(row,"external_line_id"),sku=str(row,"external_sku");Map<String,Object> old=oldLines.get(line),order=orders.get(str(row,"external_order_id")),mapping=mappings.get(sku);
            if(order==null||mapping==null)throw new ApiException(422,"订单或SKU映射不存在："+line);
            List<Map<String,Object>> allocations=parts(row,"batch_allocations"),prior=old==null?List.of():oldParts.getOrDefault(Rows.number(old,"source_row_id"),List.of());
            for(Map<String,Object> a:allocations){Map<String,Object> b=batches.get(String.valueOf(a.get("source_batch_id")));if(b==null||!sku.equals(b.get("source_sku"))||!str(row,"currency").equals(b.get("currency"))||!str(row,"currency").equals(order.get("currency")))throw new ApiException(422,"销售批次不存在或SKU/币种不一致："+line);}
            Map<String,Object> first=batches.get(str(row,"source_batch_id"));boolean same=old!=null&&sameLine(old,row.normalized(),order,mapping,first)&&sameParts(prior,allocations);
            if(same)continue;if(old!=null&&!confirm)conflict("销售明细",line);
            BigDecimal quantity=decimal(row.normalized().get("quantity")),price=decimal(row.normalized().get("unit_price")),discount=decimal(row.normalized().get("discount_amount"));BigDecimal[] totals=returned.getOrDefault(line,new BigDecimal[]{BigDecimal.ZERO,BigDecimal.ZERO});
            if(totals[0].compareTo(quantity.multiply(price).subtract(discount))>0||totals[1].compareTo(quantity)>0)throw new ApiException(409,"销售修订低于已退款/退货数量");
            if(totals[1].signum()>0&&!sameParts(prior,allocations))throw new ApiException(409,"已有退货，不能直接修改销售批次分配");
            for(Map<String,Object> a:prior)used.merge(String.valueOf(a.get("source_batch_id")),decimal(a.get("quantity")).negate(),BigDecimal::add);
            for(Map<String,Object> a:allocations){String key=String.valueOf(a.get("source_batch_id"));BigDecimal qty=decimal(a.get("quantity"));BigDecimal next=used.getOrDefault(key,BigDecimal.ZERO).add(qty);if(next.compareTo(decimal(batches.get(key).get("quantity")))>0)throw new ApiException(409,"采购批次可用数量不足："+key);used.put(key,next);}
            long sourceRow=id(ids,row.number());facts.insertLine(owner,sid,line,old==null?1:Rows.number(old,"revision")+1,sourceRow,Rows.number(order,"id"),sku,Rows.number(mapping,"mapping_version"),Rows.number(first,"id"),quantity,price,discount,str(row,"currency"),user);
            for(Map<String,Object> a:allocations)facts.allocate(owner,sid,sourceRow,Rows.number(batches.get(String.valueOf(a.get("source_batch_id"))),"id"),decimal(a.get("quantity")));
        }
        return Map.of();
    }
    private Map<Integer,Long> refunds(long sid,long user,List<PlatformCsvParser.Row> rows,Map<Integer,Long> ids,boolean confirm){
        if(rows.isEmpty())return Map.of();
        List<String> refundKeys=rows.stream().map(r->str(r,"external_refund_id")).distinct().toList();
        Map<String,Map<String,Object>> oldRefunds=index(facts.latestRefunds(sid,refundKeys),"external_refund_id");
        List<String> lineKeys=rows.stream().map(r->str(r,"external_line_id")).distinct().toList();
        Map<String,Map<String,Object>> lines=index(facts.latestLines(sid,lineKeys),"external_line_id");
        Map<Long,List<Map<String,Object>>> saleParts=new HashMap<>();if(!lines.isEmpty())for(Map<String,Object> a:facts.allocations(sid,lines.values().stream().map(l->Rows.number(l,"source_row_id")).toList()))saleParts.computeIfAbsent(Rows.number(a,"source_row_id"),k->new ArrayList<>()).add(a);
        List<Map<String,Object>> currentReturns=facts.currentReturns(sid,lineKeys);Map<String,BigDecimal> usedReturns=new HashMap<>();
        for(Map<String,Object> a:currentReturns)usedReturns.merge(a.get("external_line_id")+":"+a.get("source_batch_id"),decimal(a.get("quantity")),BigDecimal::add);
        Map<Integer,List<Map<String,Object>>> requested=new HashMap<>();
        for(PlatformCsvParser.Row row:rows){Map<String,Object> line=lines.get(str(row,"external_line_id"));if(line==null)throw new ApiException(422,"退款引用了未导入的销售明细");List<Map<String,Object>> allocated=saleParts.getOrDefault(Rows.number(line,"source_row_id"),List.of());BigDecimal qty=decimal(row.normalized().get("returned_quantity"));List<Map<String,Object>> returns;
            if(row.normalized().containsKey("return_allocations"))returns=parts(row,"return_allocations");else if(qty.signum()==0)returns=List.of();else if(allocated.size()==1)returns=List.of(Map.of("source_batch_id",allocated.getFirst().get("source_batch_id"),"quantity",qty));else throw new ApiException(422,"多批次销售退货必须提供return_allocations");
            requested.put(row.number(),returns);
        }
        Map<String,List<Map<String,Object>>> existingByLine=facts.currentRefundsForLines(sid,lineKeys).stream().collect(Collectors.groupingBy(r->String.valueOf(r.get("external_line_id"))));
        Map<String,BigDecimal[]> totals=new HashMap<>();
        for(String key:lineKeys){BigDecimal amount=BigDecimal.ZERO,returned=BigDecimal.ZERO;for(Map<String,Object> refund:existingByLine.getOrDefault(key,List.of())){amount=amount.add(decimal(refund.get("refund_amount")));returned=returned.add(decimal(refund.get("returned_quantity")));}totals.put(key,new BigDecimal[]{amount,returned});}
        for(PlatformCsvParser.Row row:rows){
            String refundKey=str(row,"external_refund_id"),lineKey=str(row,"external_line_id");Map<String,Object> line=lines.get(lineKey),old=oldRefunds.get(refundKey);
            if(line==null)throw new ApiException(422,"退款引用了未导入的销售明细："+lineKey);
            if(!str(row,"currency").equals(line.get("currency")))throw new ApiException(422,"退款币种与销售明细不一致："+refundKey);
            List<Map<String,Object>> prior=currentReturns.stream().filter(a->refundKey.equals(a.get("external_refund_id"))).toList();
            if(old!=null&&(!sameRefund(old,row.normalized(),line)||!sameParts(prior,requested.get(row.number())))&&!confirm)conflict("退款",refundKey);
        }
        for(PlatformCsvParser.Row row:rows){
            String refundKey=str(row,"external_refund_id"),lineKey=str(row,"external_line_id");Map<String,Object> line=lines.get(lineKey),old=oldRefunds.get(refundKey);
            List<Map<String,Object>> prior=currentReturns.stream().filter(a->refundKey.equals(a.get("external_refund_id"))).toList();
            if(old!=null&&sameRefund(old,row.normalized(),line)&&sameParts(prior,requested.get(row.number())))continue;
            BigDecimal[] total=totals.get(lineKey);
            if(old!=null){
                String oldLineKey=String.valueOf(old.get("external_line_id"));BigDecimal[] oldTotals=totals.get(oldLineKey);
                if(oldTotals!=null){oldTotals[0]=oldTotals[0].subtract(decimal(old.get("refund_amount")));oldTotals[1]=oldTotals[1].subtract(decimal(old.get("returned_quantity")));}
            }
            BigDecimal amount=decimal(row.normalized().get("refund_amount")),returned=decimal(row.normalized().get("returned_quantity"));
            BigDecimal lineNet=decimal(line.get("unit_price")).multiply(decimal(line.get("quantity"))).subtract(decimal(line.get("discount_amount")));
            if(total[0].add(amount).compareTo(lineNet)>0||total[1].add(returned).compareTo(decimal(line.get("quantity")))>0)throw new ApiException(409,"累计退款金额或退货数量超过销售明细："+lineKey);
            for(Map<String,Object> a:prior)usedReturns.merge(a.get("external_line_id")+":"+a.get("source_batch_id"),decimal(a.get("quantity")).negate(),BigDecimal::add);
            Map<String,Map<String,Object>> allocated=index(saleParts.getOrDefault(Rows.number(line,"source_row_id"),List.of()),"source_batch_id");
            for(Map<String,Object> a:requested.get(row.number())){String batch=String.valueOf(a.get("source_batch_id")),key=lineKey+":"+batch;Map<String,Object> sold=allocated.get(batch);BigDecimal next=usedReturns.getOrDefault(key,BigDecimal.ZERO).add(decimal(a.get("quantity")));if(sold==null||next.compareTo(decimal(sold.get("quantity")))>0)throw new ApiException(409,"退货批次未售出或累计退货超出该批次分配数量："+batch);usedReturns.put(key,next);}
            long revision=old==null?1:Rows.number(old,"revision")+1;
            facts.insertRefund(sid,refundKey,revision,id(ids,row.number()),Rows.number(line,"id"),utc(row.normalized().get("confirmed_at")),amount,str(row,"currency"),returned,(boolean)row.normalized().get("restocked"),user);
            for(Map<String,Object> a:requested.get(row.number()))facts.returnAllocation(sid,id(ids,row.number()),Rows.number(allocated.get(String.valueOf(a.get("source_batch_id"))),"procurement_batch_id"),decimal(a.get("quantity")));
            total[0]=total[0].add(amount);total[1]=total[1].add(returned);
        }
        return Map.of();
    }
    private static boolean sameOrder(Map<String,Object> old,Map<String,Object> n){return sameDate(old.get("ordered_at"),n.get("ordered_at"))&&sameDate(old.get("paid_at"),n.get("paid_at"))&&Objects.equals(old.get("status"),n.get("status"))&&Objects.equals(old.get("currency"),n.get("currency"));}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> parts(PlatformCsvParser.Row row,String key){return (List<Map<String,Object>>)row.normalized().get(key);}
    private static boolean sameParts(List<Map<String,Object>> a,List<Map<String,Object>> b){if(a.size()!=b.size())return false;Map<String,BigDecimal> values=new HashMap<>();for(Map<String,Object> p:a)values.put(String.valueOf(p.get("source_batch_id")),decimal(p.get("quantity")));return b.stream().allMatch(p->values.containsKey(String.valueOf(p.get("source_batch_id")))&&values.get(String.valueOf(p.get("source_batch_id"))).compareTo(decimal(p.get("quantity")))==0);}
    private static boolean sameBatch(Map<String,Object> old,Map<String,Object> n,Map<String,Object> mapping){return mapping!=null&&Objects.equals(old.get("source_sku"),n.get("external_sku"))&&Rows.number(old,"mapping_version")==Rows.number(mapping,"mapping_version")&&sameDate(old.get("received_at"),n.get("received_at"))&&sameDecimal(old.get("quantity"),n.get("quantity"))&&sameDecimal(old.get("unit_purchase_cost"),n.get("unit_purchase_cost"))&&Objects.equals(old.get("currency"),n.get("currency"));}
    private static boolean sameLine(Map<String,Object> old,Map<String,Object> n,Map<String,Object> order,Map<String,Object> mapping,Map<String,Object> batch){return order!=null&&mapping!=null&&batch!=null&&Rows.number(old,"order_revision_id")==Rows.number(order,"id")&&Objects.equals(old.get("source_sku"),n.get("external_sku"))&&Rows.number(old,"mapping_version")==Rows.number(mapping,"mapping_version")&&Rows.number(old,"procurement_batch_id")==Rows.number(batch,"id")&&sameDecimal(old.get("quantity"),n.get("quantity"))&&sameDecimal(old.get("unit_price"),n.get("unit_price"))&&sameDecimal(old.get("discount_amount"),n.get("discount_amount"))&&Objects.equals(old.get("currency"),n.get("currency"));}
    private static boolean sameRefund(Map<String,Object> old,Map<String,Object> n,Map<String,Object> line){return line!=null&&Objects.equals(old.get("external_line_id"),n.get("external_line_id"))&&sameDate(old.get("confirmed_at"),n.get("confirmed_at"))&&sameDecimal(old.get("refund_amount"),n.get("refund_amount"))&&Objects.equals(old.get("currency"),n.get("currency"))&&sameDecimal(old.get("returned_quantity"),n.get("returned_quantity"))&&Objects.equals(old.get("restocked"),n.get("restocked"));}
    private static boolean sameDate(Object a,Object b){return utc(a).equals(utc(b));}
    private static boolean sameDecimal(Object a,Object b){return decimal(a).compareTo(decimal(b))==0;}
    private static LocalDateTime utc(Object value){if(value instanceof LocalDateTime time)return time;if(value instanceof Timestamp timestamp)return timestamp.toLocalDateTime();if(value instanceof Instant instant)return LocalDateTime.ofInstant(instant,ZoneOffset.UTC);if(value instanceof OffsetDateTime offset)return LocalDateTime.ofInstant(offset.toInstant(),ZoneOffset.UTC);return LocalDateTime.ofInstant(Instant.parse(String.valueOf(value)),ZoneOffset.UTC);}
    private static BigDecimal decimal(Object value){return value instanceof BigDecimal n?n:new BigDecimal(String.valueOf(value));}
    private static String str(PlatformCsvParser.Row row,String key){return String.valueOf(row.normalized().get(key));}
    private static long id(Map<Integer,Long> ids,int row){Long id=ids.get(row);if(id==null)throw new IllegalStateException("来源行缺少持久化主键");return id;}
    private static Map<String,Map<String,Object>> index(List<Map<String,Object>> rows,String key){return rows.stream().collect(Collectors.toMap(r->String.valueOf(r.get(key)),Function.identity()));}
    private static void conflict(String type,String key){throw new ApiException(409,type+"已存在不同内容；确认后可使用confirm_conflicts=true追加修订："+key);}
}
