package com.sellerpilot.finance;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface FxMapper {
    @Select("SELECT id,source,source_revision,retrieved_at FROM fx_rate_sets WHERE owner_id=#{owner} ORDER BY id DESC LIMIT 100")
    List<Map<String,Object>> list(long owner);
    @Select("SELECT * FROM fx_rate_sets WHERE owner_id=#{owner} AND id=#{id}")
    Map<String,Object> get(@Param("owner")long owner,@Param("id")long id);
    @Insert("INSERT INTO fx_rate_sets(owner_id,source,source_revision,retrieved_at,created_by) VALUES(#{owner},#{body.source},#{body.source_revision},UTC_TIMESTAMP(6),#{user})")
    void insert(@Param("owner")long owner,@Param("user")long user,@Param("body")FxController.RateSet body);
    @Select("SELECT LAST_INSERT_ID()")long lastId();
    @Insert("<script>INSERT INTO fx_rates(rate_set_id,base_currency,quote_currency,effective_date,rate) VALUES <foreach collection='rates' item='r' separator=','>(#{set},#{r.base_currency},'CNY',#{r.effective_date},#{r.rate})</foreach></script>")
    void rates(@Param("set")long set,@Param("rates")List<FxController.Rate> rates);
    @Select("SELECT id,base_currency,quote_currency,effective_date,rate FROM fx_rates WHERE rate_set_id=#{set} ORDER BY effective_date,base_currency")
    List<Map<String,Object>> ratesFor(long set);
}
