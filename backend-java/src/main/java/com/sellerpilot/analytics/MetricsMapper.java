package com.sellerpilot.analytics;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MetricsMapper {
    @SelectProvider(type=MetricsSql.class,method="query")
    List<Map<String,Object>> query(@Param("sid")long sid,@Param("query")QuerySpec.Normalized query);
}
