package com.sellerpilot.finance;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MarginCalculatorTest {
    @Test void costsUseEachBatchRateAndMissingOneLeavesEventUnpriced(){
        var first=Map.of("quantity","1","unit_purchase_cost","4","cost_currency","USD","received_at","2026-10-30");
        var second=Map.of("quantity","2","unit_purchase_cost","6","cost_currency","USD","received_at","2026-10-31");
        Map<String,Object> sale=Map.of("quantity","3","unit_price","10","discount_amount","0","currency","USD",
            "paid_at","2026-11-01","received_at","2026-10-30","cost_allocations",List.of(first,second),"internal_sku","SKU");
        var rates=new ArrayList<>(List.of(rate("2026-10-30","6"),rate("2026-11-01","7")));
        Map<String,Object> input=Map.of("rates",rates,"sales",List.of(sale),"refunds",List.of());
        var calculator=new MarginCalculator();
        var incomplete=calculator.compute(input);
        assertEquals(false,incomplete.get("complete"));
        assertNull(incomplete.get("gross_margin_cny"));
        rates.add(rate("2026-10-31","7"));
        var complete=calculator.compute(input);
        assertEquals("210.00",complete.get("sales_net_cny"));
        assertEquals("108.00",complete.get("procurement_cost_cny"));
        assertEquals("102.00",complete.get("gross_margin_cny"));
    }

    private static Map<String,String> rate(String date,String value){
        return Map.of("base_currency","USD","effective_date",date,"rate",value);
    }
}
