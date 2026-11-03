package com.sellerpilot.finance;

import com.sellerpilot.common.*;
import com.sellerpilot.store.StoreAccess;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class MarginInputs {
    private final StoreAccess access;private final FxMapper fx;private final MarginMapper reports;
    public MarginInputs(StoreAccess access,FxMapper fx,MarginMapper reports){this.access=access;this.fx=fx;this.reports=reports;}
    @SuppressWarnings("unchecked")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> revalueCosts(long user,Map<String,Object> original){
        Map<String,Object> costVersions=new LinkedHashMap<>();
        for(Object id:(List<?>)original.get("store_ids")){Map<String,Object> store=access.require(user,((Number)id).longValue(),"members",false);costVersions.put(String.valueOf(id),store.get("data_version"));}
        List<Long> ids=new ArrayList<>();for(String type:List.of("sales","refunds"))for(Map<String,Object> row:(List<Map<String,Object>>)original.get(type))for(Map<String,Object> allocation:costParts(row,type))ids.add(Rows.number(allocation,"procurement_batch_id"));
        Map<Long,Map<String,Object>> costs=new HashMap<>();if(!ids.isEmpty())for(Map<String,Object> cost:reports.currentCosts(ids.stream().distinct().toList()))costs.put(Rows.number(cost,"original_id"),cost);
        Map<String,Object> result=new LinkedHashMap<>(original);
        for(String type:List.of("sales","refunds")){
            List<Map<String,Object>> rows=new ArrayList<>();
            for(Map<String,Object> row:(List<Map<String,Object>>)original.get(type)){
                List<Map<String,Object>> allocations=new ArrayList<>();for(Map<String,Object> part:costParts(row,type)){
                    long prior=Rows.number(part,"procurement_batch_id");Map<String,Object> cost=costs.get(prior);if(cost==null)throw new ApiException(409,"采购批次修订不存在");
                    if(!Objects.equals(cost.get("original_sku"),cost.get("source_sku"))||!Objects.equals(cost.get("original_currency"),cost.get("currency")))throw new ApiException(409,"采购批次SKU或币种已改变，须核对来源关联后处理");
                    Map<String,Object> nextPart=new LinkedHashMap<>(part);nextPart.putIfAbsent("original_procurement_batch_id",prior);nextPart.put("procurement_batch_id",cost.get("id"));nextPart.put("cost_batch_revision",cost.get("batch_revision"));nextPart.put("cost_source_row_id",cost.get("source_row_id"));nextPart.put("unit_purchase_cost",String.valueOf(cost.get("unit_purchase_cost")));nextPart.put("cost_currency",cost.get("currency"));nextPart.put("received_at",Rows.json(cost).get("received_at"));allocations.add(nextPart);
                }
                Map<String,Object> next=new LinkedHashMap<>(row);next.put("cost_allocations",allocations);if(!allocations.isEmpty())for(String field:List.of("procurement_batch_id","cost_batch_revision","cost_source_row_id","unit_purchase_cost","cost_currency","received_at"))next.put(field,allocations.getFirst().get(field));rows.add(next);
            }
            result.put(type,rows);
        }
        result.put("cost_basis","LATEST_BATCH_REVISIONS_AT_REVALUATION");result.put("cost_store_versions",costVersions);return result;
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> costParts(Map<String,Object> row,String type){if(row.containsKey("cost_allocations"))return (List<Map<String,Object>>)row.get("cost_allocations");Map<String,Object> part=new LinkedHashMap<>(row);part.put("quantity",row.get(type.equals("sales")?"quantity":"returned_quantity"));return List.of(part);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> capture(long user,MarginController.Query query){
        List<Long> ids=query.store_ids().stream().distinct().sorted().toList();long owner=0;
        Map<String,Object> versions=new LinkedHashMap<>();List<Map<String,Object>> sales=new ArrayList<>(),refunds=new ArrayList<>();
        for(long id:ids){
            Map<String,Object> store=access.require(user,id,"read",false);long current=Rows.number(store,"owner_id");
            if(owner!=0&&owner!=current)throw new ApiException(422,"报表店铺须属于同一所有者");owner=current;versions.put(String.valueOf(id),store.get("data_version"));
            sales.addAll(reports.sales(id,query.start().atStartOfDay(),query.end().atStartOfDay()));refunds.addAll(reports.refunds(id,query.start().atStartOfDay(),query.end().atStartOfDay()));
            if(sales.size()+refunds.size()>10000)throw new ApiException(422,"当前同步报表最多10000条销售/退款记录，请缩小范围");
        }
        Map<String,Object> set=fx.get(owner,query.fx_rate_set_id());if(set==null)throw new ApiException(404,"汇率集不存在或不属于该卖家");
        attachCosts(sales,false);attachCosts(refunds,true);
        Map<String,Object> input=new LinkedHashMap<>();input.put("owner_id",owner);input.put("store_ids",ids);input.put("store_versions",versions);input.put("start",query.start().toString());input.put("end",query.end().toString());input.put("timezone","UTC");input.put("currency","CNY");input.put("rule_version","sales-margin-v1");input.put("rounding","SUM_THEN_HALF_UP_2");input.put("fx_policy",query.fixed_rate_date()==null?"EXACT_BUSINESS_DATE":"FIXED_DATE");if(query.fixed_rate_date()!=null)input.put("fixed_rate_date",query.fixed_rate_date().toString());input.put("fx_rate_set",Rows.json(set));input.put("rates",fx.ratesFor(query.fx_rate_set_id()).stream().map(Rows::json).toList());input.put("sales",sales.stream().map(Rows::json).toList());input.put("refunds",refunds.stream().map(Rows::json).toList());return input;
    }
    private void attachCosts(List<Map<String,Object>> rows,boolean refund){
        if(rows.isEmpty())return;List<Long> ids=rows.stream().map(r->Rows.number(r,"source_row_id")).toList();Map<Long,List<Map<String,Object>>> grouped=new HashMap<>();
        for(Map<String,Object> cost:refund?reports.refundCosts(ids):reports.saleCosts(ids))grouped.computeIfAbsent(Rows.number(cost,"source_row_id"),k->new ArrayList<>()).add(Rows.json(cost));
        for(Map<String,Object> row:rows)row.put("cost_allocations",grouped.getOrDefault(Rows.number(row,"source_row_id"),List.of()));
    }
}
