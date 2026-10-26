package com.sellerpilot.importing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ImportMapper {
    @Select("SELECT * FROM import_batches WHERE store_id=#{sid} AND kind=#{kind} AND file_hash=#{hash} FOR UPDATE")
    Map<String,Object> batch(@Param("sid")long sid,@Param("kind")String kind,@Param("hash")String hash);
    @Select("<script>SELECT * FROM orders WHERE store_id=#{sid} AND external_order_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> FOR UPDATE</script>")
    List<Map<String,Object>> orders(@Param("sid")long sid,@Param("ids")List<String> ids);
    @Select("<script>SELECT * FROM products WHERE store_id=#{sid} AND sku IN <foreach collection='skus' item='sku' open='(' separator=',' close=')'>#{sku}</foreach> FOR UPDATE</script>")
    List<Map<String,Object>> products(@Param("sid")long sid,@Param("skus")List<String> skus);
    @Select("<script>SELECT i.*,o.external_order_id FROM order_items i JOIN orders o ON o.id=i.order_id AND o.store_id=i.store_id WHERE i.store_id=#{sid} AND o.external_order_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> FOR UPDATE</script>")
    List<Map<String,Object>> items(@Param("sid")long sid,@Param("ids")List<String> ids);
    @Insert("<script>INSERT INTO orders(store_id,external_order_id,ordered_at,status,currency) VALUES <foreach collection='rows' item='r' separator=','>(#{sid},#{r.external},#{r.orderedAt},#{r.status},#{r.currency})</foreach> ON DUPLICATE KEY UPDATE ordered_at=VALUES(ordered_at),status=VALUES(status)</script>")
    void saveOrders(@Param("sid")long sid,@Param("rows")List<CsvParser.Row> rows);
    record Item(long sid,long orderId,long productId,int line,int quantity,BigDecimal price,BigDecimal refund){}
    @Insert("<script>INSERT INTO order_items(store_id,order_id,product_id,line_no,quantity,unit_price,refund_amount) VALUES <foreach collection='rows' item='r' separator=','>(#{r.sid},#{r.orderId},#{r.productId},#{r.line},#{r.quantity},#{r.price},#{r.refund})</foreach> ON DUPLICATE KEY UPDATE quantity=VALUES(quantity),unit_price=VALUES(unit_price),refund_amount=VALUES(refund_amount)</script>")
    void saveItems(@Param("rows")List<Item> rows);
    @Insert("INSERT INTO import_batches(store_id,kind,file_hash,row_count,data_version,created_by) VALUES(#{sid},#{kind},#{hash},#{count},#{version},#{user})")
    void record(@Param("sid")long sid,@Param("kind")String kind,@Param("hash")String hash,@Param("count")int count,@Param("version")long version,@Param("user")long user);
    @Select("SELECT LAST_INSERT_ID()")long lastId();
}
