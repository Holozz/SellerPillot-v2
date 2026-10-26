package com.sellerpilot.auth;

import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserMapper {
    @Select("SELECT * FROM users WHERE username=#{name}")Map<String,Object> byName(String name);
    @Select("SELECT id,username FROM users WHERE id=#{id}")Map<String,Object> byId(long id);
    @Insert("INSERT INTO users(username,password_hash) VALUES(#{name},#{hash})")
    void insert(@Param("name")String name,@Param("hash")String hash);
    @Select("SELECT LAST_INSERT_ID()")long lastId();
}
