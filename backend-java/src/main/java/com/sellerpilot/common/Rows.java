package com.sellerpilot.common;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Rows {
    private Rows() {}
    public static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    public static String text(Map<String,Object> row,String key){return String.valueOf(row.get(key));}
    public static Map<String,Object> json(Map<String,Object> row){
        Map<String,Object> result=new LinkedHashMap<>();
        row.forEach((key,value)->result.put(key,value instanceof BigDecimal d?d.toPlainString():
            value instanceof Timestamp t?t.toLocalDateTime()+"Z":value instanceof LocalDateTime t?t+"Z":value));
        return result;
    }
}
