package com.sellerpilot.finance;

import java.util.*;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MarginMapper {
    @Select("<script>SELECT a.source_row_id,a.quantity,b.id AS procurement_batch_id,b.source_batch_id,b.batch_revision AS cost_batch_revision,b.source_row_id AS cost_source_row_id,b.unit_purchase_cost,b.currency AS cost_currency,b.received_at FROM procurement_batch_allocations a JOIN procurement_batch_revisions b ON b.id=a.procurement_batch_id WHERE a.source_row_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY a.id</script>")
    List<Map<String,Object>> saleCosts(@Param("ids")List<Long> ids);
    @Select("<script>SELECT a.source_row_id,a.quantity,b.id AS procurement_batch_id,b.source_batch_id,b.batch_revision AS cost_batch_revision,b.source_row_id AS cost_source_row_id,b.unit_purchase_cost,b.currency AS cost_currency,b.received_at FROM refund_batch_allocations a JOIN procurement_batch_revisions b ON b.id=a.procurement_batch_id WHERE a.source_row_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY a.id</script>")
    List<Map<String,Object>> refundCosts(@Param("ids")List<Long> ids);
    @Select("<script>SELECT original.id AS original_id,original.source_sku AS original_sku,original.currency AS original_currency,latest.id,latest.batch_revision,latest.source_row_id,latest.source_sku,latest.unit_purchase_cost,latest.currency,latest.received_at FROM procurement_batch_revisions original JOIN procurement_batch_revisions latest ON latest.store_id=original.store_id AND latest.source_batch_id=original.source_batch_id AND latest.batch_revision=(SELECT MAX(b.batch_revision) FROM procurement_batch_revisions b WHERE b.store_id=latest.store_id AND b.source_batch_id=latest.source_batch_id) WHERE original.id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Map<String,Object>> currentCosts(@Param("ids")List<Long> ids);
    @Select("""
        WITH latest_lines AS (
            SELECT external_line_id, MAX(revision) AS revision
            FROM sales_line_revisions WHERE store_id=#{sid} GROUP BY external_line_id
        ), latest_orders AS (
            SELECT external_order_id, MAX(revision) AS revision
            FROM sales_order_revisions WHERE store_id=#{sid} GROUP BY external_order_id
        )
        SELECT l.id AS line_revision_id,l.source_row_id,o.id AS order_revision_id,l.mapping_version,
               l.procurement_batch_id,l.quantity,l.unit_price,l.discount_amount,l.currency,l.external_line_id,
               l.store_id,o.paid_at,p.internal_sku,b.unit_purchase_cost,b.currency AS cost_currency,b.received_at
        FROM latest_lines ll
        STRAIGHT_JOIN sales_line_revisions l ON l.store_id=#{sid} AND l.external_line_id=ll.external_line_id AND l.revision=ll.revision
        STRAIGHT_JOIN sales_order_revisions original_order ON original_order.store_id=l.store_id AND original_order.id=l.order_revision_id
        STRAIGHT_JOIN latest_orders lo ON lo.external_order_id=original_order.external_order_id
        STRAIGHT_JOIN sales_order_revisions o ON o.store_id=l.store_id AND o.external_order_id=lo.external_order_id AND o.revision=lo.revision
        STRAIGHT_JOIN procurement_batch_revisions b ON b.id=l.procurement_batch_id
        STRAIGHT_JOIN store_product_mapping_revisions m ON m.store_id=l.store_id AND m.source_sku=l.source_sku AND m.mapping_version=l.mapping_version
        STRAIGHT_JOIN catalog_products p ON p.id=m.catalog_product_id
        WHERE o.paid_at>=#{start} AND o.paid_at<#{end} AND o.status IN ('paid','shipped')
        ORDER BY l.id LIMIT 10001
        """)
    List<Map<String,Object>> sales(@Param("sid")long sid,@Param("start")LocalDateTime start,@Param("end")LocalDateTime end);
    @Select("SELECT r.id AS refund_revision_id,r.external_refund_id,r.source_row_id,r.sales_line_revision_id,r.refund_amount,r.currency,r.returned_quantity,r.restocked,r.confirmed_at,r.store_id,l.external_line_id,p.internal_sku,b.id AS procurement_batch_id,b.unit_purchase_cost,b.currency AS cost_currency,b.received_at FROM sales_refund_revisions r JOIN sales_line_revisions l ON l.store_id=r.store_id AND l.id=r.sales_line_revision_id JOIN procurement_batch_revisions b ON b.id=l.procurement_batch_id JOIN store_product_mapping_revisions m ON m.store_id=l.store_id AND m.source_sku=l.source_sku AND m.mapping_version=l.mapping_version JOIN catalog_products p ON p.id=m.catalog_product_id WHERE r.store_id=#{sid} AND r.revision=(SELECT MAX(r2.revision) FROM sales_refund_revisions r2 WHERE r2.store_id=r.store_id AND r2.external_refund_id=r.external_refund_id) AND r.confirmed_at>=#{start} AND r.confirmed_at<#{end} ORDER BY r.id LIMIT 10001")
    List<Map<String,Object>> refunds(@Param("sid")long sid,@Param("start")LocalDateTime start,@Param("end")LocalDateTime end);
    @Insert("INSERT INTO margin_reports(id,owner_id,store_ids,input_manifest,result,created_by) VALUES(#{id},#{owner},CAST(#{stores} AS JSON),CAST(#{manifest} AS JSON),CAST(#{result} AS JSON),#{user})")
    void save(@Param("id")String id,@Param("owner")long owner,@Param("stores")String stores,@Param("manifest")String manifest,@Param("result")String result,@Param("user")long user);
    @Select("SELECT * FROM margin_reports WHERE id=#{id}")Map<String,Object> get(String id);
    @Select("SELECT r.id,r.created_at,r.store_ids,JSON_UNQUOTE(JSON_EXTRACT(r.input_manifest,'$.start')) AS start_date,JSON_UNQUOTE(JSON_EXTRACT(r.input_manifest,'$.end')) AS end_date,JSON_EXTRACT(r.result,'$.complete') AS complete,JSON_UNQUOTE(JSON_EXTRACT(r.result,'$.known_margin_cny')) AS known_margin_cny FROM margin_reports r WHERE JSON_CONTAINS(r.store_ids,CAST(#{sid} AS JSON)) AND NOT EXISTS(SELECT 1 FROM JSON_TABLE(r.store_ids,'$[*]' COLUMNS(sid BIGINT PATH '$')) targets WHERE NOT EXISTS(SELECT 1 FROM stores s WHERE s.id=targets.sid AND (s.owner_id=#{user} OR EXISTS(SELECT 1 FROM store_memberships m WHERE m.store_id=s.id AND m.user_id=#{user} AND m.active=TRUE)))) ORDER BY r.created_at DESC,r.id DESC LIMIT 50")
    List<Map<String,Object>> history(@Param("user")long user,@Param("sid")long sid);
}
