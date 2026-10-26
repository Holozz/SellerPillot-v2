package com.sellerpilot.product;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ProductMapper {
    @Select("SELECT * FROM products WHERE store_id=#{sid} AND id=#{id}")Map<String,Object> get(@Param("sid")long sid,@Param("id")long id);
    @Select("SELECT * FROM products WHERE store_id=#{sid} AND id=#{id} FOR UPDATE")Map<String,Object> lock(@Param("sid")long sid,@Param("id")long id);
    @Select("SELECT * FROM products WHERE store_id=#{sid} AND sku=#{sku}")Map<String,Object> bySku(@Param("sid")long sid,@Param("sku")String sku);
    @Select("SELECT * FROM products WHERE store_id=#{sid} AND id>#{after} ORDER BY id LIMIT #{limit}")
    List<Map<String,Object>> list(@Param("sid")long sid,@Param("after")long after,@Param("limit")int limit);
    @Select("SELECT * FROM products WHERE store_id=#{sid} AND stock<#{threshold} ORDER BY stock,id LIMIT #{limit}")
    List<Map<String,Object>> low(@Param("sid")long sid,@Param("threshold")int threshold,@Param("limit")int limit);
    @Insert("INSERT INTO products(store_id,sku,title,price,currency,stock) VALUES(#{sid},#{sku},#{title},#{price},#{currency},#{stock})")
    void insert(@Param("sid")long sid,@Param("sku")String sku,@Param("title")String title,@Param("price")BigDecimal price,@Param("currency")String currency,@Param("stock")int stock);
    @Select("SELECT LAST_INSERT_ID()")long lastId();
    @Update("UPDATE products SET title=#{title},price=#{price},currency=#{currency},stock=#{stock},version=version+1 WHERE id=#{id} AND store_id=#{sid}")
    void update(@Param("sid")long sid,@Param("id")long id,@Param("title")String title,@Param("price")BigDecimal price,@Param("currency")String currency,@Param("stock")int stock);
    @Update("UPDATE products SET title=#{title},version=version+1 WHERE store_id=#{sid} AND id=#{id}")
    void title(@Param("sid")long sid,@Param("id")long id,@Param("title")String title);
    @Delete("DELETE FROM products WHERE store_id=#{sid} AND id=#{id}")void delete(@Param("sid")long sid,@Param("id")long id);
}
