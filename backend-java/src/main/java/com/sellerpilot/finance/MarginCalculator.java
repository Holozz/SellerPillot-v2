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
            BigDecimal amount=sale?n(row.get("quantity")).multiply(n(row.get("unit_price"))).subtract(n(row.get("discount_amount"))):n(row.get("refund_amount"));
            BigDecimal purchase=n(row.get("unit_purchase_cost")).multiply(sale?n(row.get("quantity")):restocked?n(row.get("returned_quantity")):BigDecimal.ZERO);
            BigDecimal rate=rate(rates,String.valueOf(row.get("currency")),date),costRate=purchase.signum()==0?BigDecimal.ONE:rate(rates,String.valueOf(row.get("cost_currency")),costDate);
            Map<String,Object> detail=new LinkedHashMap<>(row);detail.put("event",kind);detail.put("original_amount",amount.toPlainString());detail.put("original_purchase_cost",purchase.toPlainString());detail.put("revenue_rate_date",date);detail.put("cost_rate_date",costDate);detail.put("revenue_rate",rate==null?null:rate.toPlainString());detail.put("cost_rate",costRate==null?null:costRate.toPlainString());
            boolean priced=rate!=null&&costRate!=null;detail.put("complete",priced);
            if(!priced){missing.add(detail);details.add(detail);continue;}
            BigDecimal converted=amount.multiply(rate),convertedCost=purchase.multiply(costRate),margin=sale?converted.subtract(convertedCost):convertedCost.subtract(converted);
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
