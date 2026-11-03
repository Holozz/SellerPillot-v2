package com.sellerpilot.finance;

import java.math.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MarginCalculator {
    @SuppressWarnings("unchecked")
    public Map<String,Object> compute(Map<String,Object> input){
        Map<String,BigDecimal> rates=new HashMap<>();
        for(Map<String,Object> rate:(List<Map<String,Object>>)input.get("rates"))rates.put(rate.get("base_currency")+":"+rate.get("effective_date"),n(rate.get("rate")));
        List<Map<String,Object>> details=new ArrayList<>();List<Map<String,Object>> missing=new ArrayList<>();
        BigDecimal sales=BigDecimal.ZERO,cost=BigDecimal.ZERO,refunds=BigDecimal.ZERO,returned=BigDecimal.ZERO;
        Map<String,BigDecimal> products=new TreeMap<>();int complete=0;
        for(String kind:List.of("sales","refunds"))for(Map<String,Object> row:(List<Map<String,Object>>)input.get(kind)){
            boolean sale=kind.equals("sales"),restocked=Boolean.TRUE.equals(row.get("restocked"));
            String date=String.valueOf(row.get(sale?"paid_at":"confirmed_at")).substring(0,10),costDate=String.valueOf(row.get("received_at")).substring(0,10);
            String businessDate=date,purchaseDate=costDate;
            if("FIXED_DATE".equals(input.get("fx_policy"))){date=String.valueOf(input.get("fixed_rate_date"));costDate=date;}
            BigDecimal amount=sale?n(row.get("quantity")).multiply(n(row.get("unit_price"))).subtract(n(row.get("discount_amount"))):n(row.get("refund_amount"));
            BigDecimal purchase=BigDecimal.ZERO,convertedCost=BigDecimal.ZERO;boolean costComplete=true;
            List<Map<String,Object>> allocations=(List<Map<String,Object>>)row.get("cost_allocations");
            if(allocations==null)allocations=List.of(Map.of("quantity",row.get(sale?"quantity":"returned_quantity"),"unit_purchase_cost",row.get("unit_purchase_cost"),"cost_currency",row.get("cost_currency"),"received_at",row.get("received_at"),"procurement_batch_id",row.get("procurement_batch_id")));
            if(allocations.stream().map(a->n(a.get("quantity"))).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(n(row.get(sale?"quantity":"returned_quantity")))!=0)throw new com.sellerpilot.common.ApiException(409,"报表成本分配数量与销售或退货数量不一致");
            List<Map<String,Object>> pricedCosts=new ArrayList<>();
            for(Map<String,Object> allocation:allocations){BigDecimal original=n(allocation.get("unit_purchase_cost")).multiply(sale||restocked?n(allocation.get("quantity")):BigDecimal.ZERO);String at="FIXED_DATE".equals(input.get("fx_policy"))?String.valueOf(input.get("fixed_rate_date")):String.valueOf(allocation.get("received_at")).substring(0,10);BigDecimal fx=original.signum()==0?BigDecimal.ONE:rate(rates,String.valueOf(allocation.get("cost_currency")),at);purchase=purchase.add(original);Map<String,Object> part=new LinkedHashMap<>(allocation);part.put("rate_date",at);part.put("rate",fx==null?null:fx.toPlainString());part.put("original_cost",original.toPlainString());if(fx==null)costComplete=false;else{BigDecimal value=original.multiply(fx);convertedCost=convertedCost.add(value);part.put("cost_cny_unrounded",value.toPlainString());}pricedCosts.add(part);}
            BigDecimal rate=rate(rates,String.valueOf(row.get("currency")),date);
            Map<String,Object> detail=new LinkedHashMap<>(row);detail.put("cost_allocations",pricedCosts);detail.put("event",kind);detail.put("original_amount",amount.toPlainString());detail.put("original_purchase_cost",purchase.toPlainString());detail.put("revenue_rate_date",date);detail.put("cost_rate_date",allocations.size()>1?"MULTIPLE":costDate);detail.put("revenue_rate",rate==null?null:rate.toPlainString());detail.put("cost_rate",!costComplete?null:allocations.size()>1?"MULTIPLE":pricedCosts.isEmpty()?"1":pricedCosts.getFirst().get("rate"));
            detail.put("business_date",businessDate);detail.put("purchase_date",purchaseDate);
            boolean priced=rate!=null&&costComplete;detail.put("complete",priced);
            if(!priced){missing.add(detail);details.add(detail);continue;}
            BigDecimal converted=amount.multiply(rate),margin=sale?converted.subtract(convertedCost):convertedCost.subtract(converted);
            if(sale){sales=sales.add(converted);cost=cost.add(convertedCost);}else{refunds=refunds.add(converted);returned=returned.add(convertedCost);}
            detail.put("amount_cny_unrounded",converted.toPlainString());detail.put("purchase_cost_cny_unrounded",convertedCost.toPlainString());detail.put("margin_cny_unrounded",margin.toPlainString());details.add(detail);products.merge(String.valueOf(row.get("internal_sku")),margin,BigDecimal::add);complete++;
        }
        BigDecimal known=sales.subtract(refunds).subtract(cost).add(returned);
        Map<String,Object> result=new LinkedHashMap<>();result.put("currency","CNY");result.put("complete",missing.isEmpty());result.put("event_count",details.size());result.put("priced_event_count",complete);result.put("sales_net_cny",money(sales));result.put("refunds_cny",money(refunds));result.put("procurement_cost_cny",money(cost));result.put("returned_cost_cny",money(returned));result.put("known_margin_cny",money(known));result.put("gross_margin_cny",missing.isEmpty()?money(known):null);result.put("unpriced_events",missing);result.put("details",details);
        result.put("products",products.entrySet().stream().sorted(Map.Entry.<String,BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).map(e->Map.of("internal_sku",e.getKey(),"known_margin_cny",money(e.getValue()))).toList());return result;
    }
    private static BigDecimal rate(Map<String,BigDecimal> rates,String currency,String date){return currency.equals("CNY")?BigDecimal.ONE:rates.get(currency+":"+date);}
    private static BigDecimal n(Object value){return new BigDecimal(String.valueOf(value));}
    private static String money(BigDecimal value){return value.setScale(2,RoundingMode.HALF_UP).toPlainString();}
}
