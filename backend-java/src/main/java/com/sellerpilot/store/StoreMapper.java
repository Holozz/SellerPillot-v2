package com.sellerpilot.store;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface StoreMapper {
    @Select("SELECT * FROM stores WHERE id=#{sid}") Map<String,Object> get(long sid);
    @Select("SELECT * FROM stores WHERE id=#{sid} FOR UPDATE") Map<String,Object> lock(long sid);
    @Select("SELECT role FROM store_memberships WHERE store_id=#{sid} AND user_id=#{uid} AND active=TRUE")
    String role(@Param("sid")long sid,@Param("uid")long uid);
    @Select("SELECT role FROM store_memberships WHERE store_id=#{sid} AND user_id=#{uid} AND active=TRUE FOR UPDATE")
    String lockedRole(@Param("sid")long sid,@Param("uid")long uid);
    @Select("SELECT s.* FROM stores s WHERE s.owner_id=#{uid} OR EXISTS(SELECT 1 FROM store_memberships m WHERE m.store_id=s.id AND m.user_id=#{uid} AND m.active=TRUE) ORDER BY s.id LIMIT 100")
    List<Map<String,Object>> list(long uid);
    @Insert("INSERT INTO stores(owner_id,name,platform) VALUES(#{uid},#{name},#{platform})")
    void insert(@Param("uid")long uid,@Param("name")String name,@Param("platform")String platform);
    @Select("SELECT LAST_INSERT_ID()")long lastId();
    @Update("UPDATE stores SET data_version=data_version+1 WHERE id=#{sid}")void bump(long sid);
    @Select("SELECT m.*,u.username FROM store_memberships m JOIN users u ON u.id=m.user_id WHERE m.store_id=#{sid}")
    List<Map<String,Object>> members(long sid);
    @Insert("INSERT INTO store_memberships(store_id,user_id,role,active) VALUES(#{sid},#{uid},#{role},#{active}) ON DUPLICATE KEY UPDATE role=#{role},active=#{active}")
    void member(@Param("sid")long sid,@Param("uid")long uid,@Param("role")String role,@Param("active")boolean active);
}
