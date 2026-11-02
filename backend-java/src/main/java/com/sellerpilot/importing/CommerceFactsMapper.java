package com.sellerpilot.importing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CommerceFactsMapper {
    @Select("<script>SELECT o.* FROM sales_order_revisions o JOIN (SELECT external_order_id,MAX(revision) revision FROM sales_order_revisions WHERE store_id=#{sid} AND external_order_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> GROUP BY external_order_id) x ON x.external_order_id=o.external_order_id AND x.revision=o.revision WHERE o.store_id=#{sid}</script>")
    List<Map<String,Object>> latestOrders(@Param("sid")long sid,@Param("ids")List<String> ids);
    @Select("<script>SELECT l.* FROM sales_line_revisions l JOIN (SELECT external_line_id,MAX(revision) revision FROM sales_line_revisions WHERE store_id=#{sid} AND external_line_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> GROUP BY external_line_id) x ON x.external_line_id=l.external_line_id AND x.revision=l.revision WHERE l.store_id=#{sid}</script>")
    List<Map<String,Object>> latestLines(@Param("sid")long sid,@Param("ids")List<String> ids);
    @Select("<script>SELECT b.* FROM procurement_batch_revisions b JOIN (SELECT source_batch_id,MAX(batch_revision) batch_revision FROM procurement_batch_revisions WHERE store_id=#{sid} AND source_batch_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> GROUP BY source_batch_id) x ON x.source_batch_id=b.source_batch_id AND x.batch_revision=b.batch_revision WHERE b.store_id=#{sid}</script>")
    List<Map<String,Object>> latestBatches(@Param("sid")long sid,@Param("ids")List<String> ids);
    @Select("<script>SELECT r.*,l.external_line_id FROM sales_refund_revisions r JOIN sales_line_revisions l ON l.store_id=r.store_id AND l.id=r.sales_line_revision_id JOIN (SELECT external_refund_id,MAX(revision) revision FROM sales_refund_revisions WHERE store_id=#{sid} AND external_refund_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> GROUP BY external_refund_id) x ON x.external_refund_id=r.external_refund_id AND x.revision=r.revision WHERE r.store_id=#{sid}</script>")
    List<Map<String,Object>> latestRefunds(@Param("sid")long sid,@Param("ids")List<String> ids);
    @Select("SELECT * FROM store_product_mapping_revisions WHERE store_id=#{sid} AND source_sku=#{sku} ORDER BY mapping_version DESC LIMIT 1")
    Map<String,Object> latestMapping(@Param("sid")long sid,@Param("sku")String sku);
    @Select("<script>SELECT b.source_batch_id,COALESCE(SUM(a.quantity),0) AS allocated_quantity FROM procurement_batch_allocations a JOIN procurement_batch_revisions b ON b.id=a.procurement_batch_id AND b.store_id=a.store_id JOIN sales_line_revisions l ON l.store_id=a.store_id AND l.source_row_id=a.source_row_id WHERE b.store_id=#{sid} AND b.source_batch_id IN <foreach collection='batches' item='batch' open='(' separator=',' close=')'>#{batch}</foreach> AND l.revision=(SELECT MAX(l2.revision) FROM sales_line_revisions l2 WHERE l2.store_id=l.store_id AND l2.external_line_id=l.external_line_id) GROUP BY b.source_batch_id</script>")
    List<Map<String,Object>> allocatedForBatches(@Param("sid")long sid,@Param("batches")List<String> batches);
    @Select("SELECT l.* FROM sales_line_revisions l WHERE l.store_id=#{sid} AND l.external_line_id=#{line} ORDER BY l.revision DESC LIMIT 1")
    Map<String,Object> latestLine(@Param("sid")long sid,@Param("line")String line);
    @Select("<script>SELECT r.*,l.external_line_id FROM sales_refund_revisions r JOIN sales_line_revisions l ON l.store_id=r.store_id AND l.id=r.sales_line_revision_id WHERE r.store_id=#{sid} AND l.external_line_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> AND r.revision=(SELECT MAX(r2.revision) FROM sales_refund_revisions r2 WHERE r2.store_id=r.store_id AND r2.external_refund_id=r.external_refund_id)</script>")
    List<Map<String,Object>> currentRefundsForLines(@Param("sid")long sid,@Param("ids")List<String> ids);
    @Insert("INSERT INTO sales_order_revisions(store_id,external_order_id,revision,source_row_id,ordered_at,paid_at,status,currency,created_by) VALUES(#{sid},#{external},#{revision},#{sourceRow},#{orderedAt},#{paidAt},#{status},#{currency},#{user})")
    void insertOrder(@Param("sid")long sid,@Param("external")String external,@Param("revision")long revision,@Param("sourceRow")long sourceRow,@Param("orderedAt")LocalDateTime orderedAt,@Param("paidAt")LocalDateTime paidAt,@Param("status")String status,@Param("currency")String currency,@Param("user")long user);
    @Insert("INSERT INTO procurement_batch_revisions(owner_id,store_id,source_batch_id,batch_revision,source_sku,mapping_version,received_at,quantity,unit_purchase_cost,currency,source_row_id) VALUES(#{owner},#{sid},#{sourceBatch},#{revision},#{sku},#{mapping},#{receivedAt},#{quantity},#{unitCost},#{currency},#{sourceRow})")
    void insertBatch(@Param("owner")long owner,@Param("sid")long sid,@Param("sourceBatch")String sourceBatch,@Param("revision")long revision,@Param("sku")String sku,@Param("mapping")long mapping,@Param("receivedAt")LocalDateTime receivedAt,@Param("quantity")BigDecimal quantity,@Param("unitCost")BigDecimal unitCost,@Param("currency")String currency,@Param("sourceRow")long sourceRow);
    @Insert("INSERT INTO sales_line_revisions(owner_id,store_id,external_line_id,revision,source_row_id,order_revision_id,source_sku,mapping_version,procurement_batch_id,quantity,unit_price,discount_amount,currency,created_by) VALUES(#{owner},#{sid},#{line},#{revision},#{sourceRow},#{orderRevision},#{sku},#{mapping},#{batchId},#{quantity},#{unitPrice},#{discount},#{currency},#{user})")
    void insertLine(@Param("owner")long owner,@Param("sid")long sid,@Param("line")String line,@Param("revision")long revision,@Param("sourceRow")long sourceRow,@Param("orderRevision")long orderRevision,@Param("sku")String sku,@Param("mapping")long mapping,@Param("batchId")long batchId,@Param("quantity")BigDecimal quantity,@Param("unitPrice")BigDecimal unitPrice,@Param("discount")BigDecimal discount,@Param("currency")String currency,@Param("user")long user);
    @Insert("INSERT INTO procurement_batch_allocations(owner_id,store_id,source_row_id,procurement_batch_id,quantity) VALUES(#{owner},#{sid},#{sourceRow},#{batchId},#{quantity})")
    void allocate(@Param("owner")long owner,@Param("sid")long sid,@Param("sourceRow")long sourceRow,@Param("batchId")long batchId,@Param("quantity")BigDecimal quantity);
    @Insert("INSERT INTO sales_refund_revisions(store_id,external_refund_id,revision,source_row_id,sales_line_revision_id,confirmed_at,refund_amount,currency,returned_quantity,restocked,created_by) VALUES(#{sid},#{refund},#{revision},#{sourceRow},#{lineId},#{confirmedAt},#{amount},#{currency},#{returned},#{restocked},#{user})")
    void insertRefund(@Param("sid")long sid,@Param("refund")String refund,@Param("revision")long revision,@Param("sourceRow")long sourceRow,@Param("lineId")long lineId,@Param("confirmedAt")LocalDateTime confirmedAt,@Param("amount")BigDecimal amount,@Param("currency")String currency,@Param("returned")BigDecimal returned,@Param("restocked")boolean restocked,@Param("user")long user);
}
