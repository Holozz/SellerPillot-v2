-- With the skewed million-order fixture, store_id-first encouraged a product-first
-- plan that scanned the hot store item range once per product. order_id leads the
-- actual equality join and avoids offering that misleading store-only access path.
-- InnoDB may have replaced the implicit FK index with the V3 covering index.
-- Restore a narrow FK-supporting index before dropping that covering index.
CREATE INDEX ix_items_store_order ON order_items(store_id,order_id);
DROP INDEX ix_items_metrics ON order_items;
CREATE INDEX ix_items_metrics ON order_items(order_id,store_id,product_id,quantity,unit_price,refund_amount);
