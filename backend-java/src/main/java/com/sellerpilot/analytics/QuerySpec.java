package com.sellerpilot.analytics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sellerpilot.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

public record QuerySpec(String metric,@JsonProperty("group_by")String groupBy,OffsetDateTime start,
                        OffsetDateTime end,List<String> statuses,Integer limit,Integer threshold){
    public record Normalized(String metric,String groupBy,LocalDateTime start,LocalDateTime end,
                             List<String> statuses,int limit,Integer threshold){
        public java.util.Map<String,Object> asMap(){
            java.util.Map<String,Object> result=new java.util.LinkedHashMap<>();
            result.put("metric",metric);result.put("group_by",groupBy);result.put("start",start==null?null:start+"Z");
            result.put("end",end==null?null:end+"Z");result.put("statuses",statuses);result.put("limit",limit);result.put("threshold",threshold);return result;
        }
        public String canonical(){return "v1|"+metric+"|"+groupBy+"|"+start+"|"+end+"|"+String.join(",",statuses)+"|"+limit+"|"+threshold;}
        public String hash(){
            try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical().getBytes(StandardCharsets.UTF_8)));}
            catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        }
    }
    public Normalized normalize(){
        String m=metric==null?"sales":metric;String g=groupBy==null?"product":groupBy;int n=limit==null?20:limit;
        if(!Set.of("sales","quantity","orders","stock").contains(m)||!Set.of("product","currency").contains(g))throw new ApiException(422,"不支持的指标或分组");
        if(n<1||n>100||(threshold!=null&&threshold<0))throw new ApiException(422,"数量或库存阈值无效");
        List<String> states=statuses==null?List.of("paid","shipped"):statuses;
        if(states.isEmpty()||states.size()>4||states.stream().anyMatch(s->s==null||!Set.of("pending","paid","shipped","cancelled").contains(s)))throw new ApiException(422,"订单状态无效");
        states=states.stream().distinct().sorted().toList();
        LocalDateTime a=start==null?null:LocalDateTime.ofInstant(start.toInstant(),ZoneOffset.UTC);
        LocalDateTime b=end==null?null:LocalDateTime.ofInstant(end.toInstant(),ZoneOffset.UTC);
        if(!m.equals("stock")&&(a==null||b==null||!a.isBefore(b)||java.time.Duration.between(a,b).toSeconds()>366L*86400))throw new ApiException(422,"查询必须提供带时区、递增且不超过366天的区间");
        return new Normalized(m,g,a,b,states,n,threshold);
    }
}
