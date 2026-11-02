package com.sellerpilot.finance;

import com.sellerpilot.common.ApiException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MarginComparison {
    private final MarginCalculator calculator;
    public MarginComparison(MarginCalculator calculator){this.calculator=calculator;}
    @SuppressWarnings("unchecked")
    public Map<String,Object> compare(Map<String,Object> before,Map<String,Object> after){
        for(String field:List.of("store_ids","start","end","timezone","currency","rule_version","rounding","fx_policy"))if(!Objects.equals(before.get(field),after.get(field)))throw new ApiException(422,"报表范围或口径不可比："+field);
        if("FIXED_DATE".equals(before.get("fx_policy"))&&(!Objects.equals(before.get("fixed_rate_date"),after.get("fixed_rate_date"))||!Objects.equals(((Map<?,?>)before.get("fx_rate_set")).get("id"),((Map<?,?>)after.get("fx_rate_set")).get("id"))))throw new ApiException(422,"固定汇率对比须使用同一汇率版本和固定日期");
        Map<String,Object> repriced=new LinkedHashMap<>(after);repriced.put("rates",before.get("rates"));repriced.put("fx_rate_set",before.get("fx_rate_set"));
        Map<String,Object> a=calculator.compute(before),middle=calculator.compute(repriced),b=calculator.compute(after);
        if(!Boolean.TRUE.equals(a.get("complete"))||!Boolean.TRUE.equals(middle.get("complete"))||!Boolean.TRUE.equals(b.get("complete")))throw new ApiException(422,"报表或旧汇率重算存在未定价事件，不能分解完整毛利差额");
        Map<String,Object> components=new LinkedHashMap<>();for(String field:List.of("sales_net_cny","refunds_cny","procurement_cost_cny","returned_cost_cny"))components.put(field,delta(b,a,field));
        Map<String,Map<String,Object>> oldEvents=index((List<Map<String,Object>>)a.get("details")),newEvents=index((List<Map<String,Object>>)b.get("details"));
        Set<String> keys=new TreeSet<>(oldEvents.keySet());keys.addAll(newEvents.keySet());List<Map<String,Object>> changed=new ArrayList<>();
        for(String key:keys){Map<String,Object> old=oldEvents.get(key),next=newEvents.get(key);if(Objects.equals(old,next))continue;
            Map<String,Object> event=new LinkedHashMap<>();event.put("event_key",key);event.put("before",old);event.put("after",next);event.put("margin_delta_cny",value(next).subtract(value(old)).setScale(2,java.math.RoundingMode.HALF_UP).toPlainString());changed.add(event);
        }
        return Map.of("decomposition_rule","NEW_FACTS_AT_OLD_RATES_THEN_NEW_RATES_V1","before_margin_cny",a.get("gross_margin_cny"),"after_margin_cny",b.get("gross_margin_cny"),"margin_delta_cny",delta(b,a,"gross_margin_cny"),"business_data_delta_cny",delta(middle,a,"gross_margin_cny"),"fx_delta_cny",delta(b,middle,"gross_margin_cny"),"component_deltas",components,"changed_events",changed);
    }
    private static String delta(Map<String,Object> a,Map<String,Object> b,String field){return new BigDecimal(String.valueOf(a.get(field))).subtract(new BigDecimal(String.valueOf(b.get(field)))).toPlainString();}
    private static BigDecimal value(Map<String,Object> event){return event==null?BigDecimal.ZERO:new BigDecimal(String.valueOf(event.get("margin_cny_unrounded")));}
    private static Map<String,Map<String,Object>> index(List<Map<String,Object>> rows){Map<String,Map<String,Object>> result=new LinkedHashMap<>();for(Map<String,Object> row:rows){String key=row.get("event")+":"+row.get("store_id")+":"+("sales".equals(row.get("event"))?row.get("external_line_id"):row.get("external_refund_id"));result.put(key,row);}return result;}
}
