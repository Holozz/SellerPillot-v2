package com.sellerpilot.importing;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import com.sellerpilot.common.ApiException;

class CsvParserTest {
    final CsvParser parser=new CsvParser();
    @Test void rejectsInvalidDatesAndReportsBoundedErrors(){
        StringBuilder file=new StringBuilder("external_order_id,ordered_at,status,currency\n");
        for(int i=0;i<100;i++)file.append("O").append(i).append(",2026-01-01,paid,USD\n");
        ApiException error=assertThrows(ApiException.class,()->parser.parse("orders",file.toString().getBytes(StandardCharsets.UTF_8)));
        java.util.Map<?,?> detail=(java.util.Map<?,?>)error.detail;
        assertEquals(100,detail.get("total_errors"));assertEquals(50,((java.util.List<?>)detail.get("errors")).size());assertEquals(true,detail.get("truncated"));
    }
    @Test void utf8BomAndTimezoneNormalizeToUtc(){
        CsvParser.Parsed result=parser.parse("orders","\uFEFFexternal_order_id,ordered_at,status,currency\nO1,2026-10-01T08:00:00+08:00,paid,USD\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(java.time.LocalDateTime.parse("2026-10-01T00:00:00"),result.rows().getFirst().orderedAt());
    }
}
