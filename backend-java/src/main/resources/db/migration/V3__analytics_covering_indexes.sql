-- QuerySpec filters by store, status and UTC interval. Currency is needed for grouping.
CREATE INDEX ix_orders_metrics ON orders(store_id,status,ordered_at,currency);
-- The million-order fixture showed random table lookups dominating the item join.
-- Keep the business-key unique index; this covering index trades import/storage cost
-- for avoiding item table reads in the bounded analytics query.
CREATE INDEX ix_items_metrics ON order_items(store_id,order_id,product_id,quantity,unit_price,refund_amount);
