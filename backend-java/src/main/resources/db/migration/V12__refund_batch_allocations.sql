CREATE TABLE refund_batch_allocations (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 store_id BIGINT NOT NULL,
 source_row_id BIGINT NOT NULL,
 procurement_batch_id BIGINT NOT NULL,
 quantity DECIMAL(18,6) NOT NULL,
 UNIQUE(source_row_id,procurement_batch_id),
 CHECK(quantity>0),
 FOREIGN KEY(store_id,source_row_id) REFERENCES source_import_rows(store_id,id),
 FOREIGN KEY(procurement_batch_id) REFERENCES procurement_batch_revisions(id)
) ENGINE=InnoDB;
INSERT INTO refund_batch_allocations(store_id,source_row_id,procurement_batch_id,quantity)
 SELECT r.store_id,r.source_row_id,l.procurement_batch_id,r.returned_quantity
 FROM sales_refund_revisions r JOIN sales_line_revisions l ON l.store_id=r.store_id AND l.id=r.sales_line_revision_id
 WHERE r.returned_quantity>0;
