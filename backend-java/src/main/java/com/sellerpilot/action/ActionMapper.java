package com.sellerpilot.action;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ActionMapper {
    @Insert("INSERT INTO action_proposals(id,store_id,product_id,user_id,product_version,original_title,proposed_title,payload_hash,expires_at) VALUES(#{id},#{sid},#{pid},#{user},#{version},#{original},#{title},#{hash},#{expires})")
    void create(@Param("id")String id,@Param("sid")long sid,@Param("pid")long pid,@Param("user")long user,@Param("version")long version,@Param("original")String original,@Param("title")String title,@Param("hash")String hash,@Param("expires")LocalDateTime expires);
    @Select("SELECT * FROM action_proposals WHERE store_id=#{sid} ORDER BY created_at DESC,id LIMIT 100")List<Map<String,Object>> list(long sid);
    @Select("SELECT * FROM action_proposals WHERE store_id=#{sid} AND id=#{id}")Map<String,Object> get(@Param("sid")long sid,@Param("id")String id);
    @Select("SELECT * FROM action_proposals WHERE store_id=#{sid} AND id=#{id} FOR UPDATE")Map<String,Object> lock(@Param("sid")long sid,@Param("id")String id);
    @Update("UPDATE action_proposals SET status=#{status} WHERE id=#{id}")void status(@Param("id")String id,@Param("status")String status);
    @Select("SELECT proposal_id FROM action_executions WHERE store_id=#{sid} AND idempotency_key=#{key} FOR UPDATE")String proposalForKey(@Param("sid")long sid,@Param("key")String key);
    @Select("SELECT receipt FROM action_executions WHERE proposal_id=#{id}")String receipt(String id);
    @Insert("INSERT INTO action_executions(id,store_id,proposal_id,idempotency_key,receipt) VALUES(#{id},#{sid},#{proposal},#{key},#{receipt})")
    void execution(@Param("id")String id,@Param("sid")long sid,@Param("proposal")String proposal,@Param("key")String key,@Param("receipt")String receipt);
    @Insert("INSERT INTO audit_logs(store_id,user_id,action,target,detail) VALUES(#{sid},#{user},#{action},#{target},#{detail})")
    void audit(@Param("sid")long sid,@Param("user")long user,@Param("action")String action,@Param("target")String target,@Param("detail")String detail);
    @Select("SELECT * FROM audit_logs WHERE store_id=#{sid} ORDER BY id DESC LIMIT 100")List<Map<String,Object>> audits(long sid);
}
