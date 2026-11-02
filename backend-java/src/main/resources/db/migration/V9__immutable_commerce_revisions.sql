-- Canonical facts are append-only revisions linked to the exact source row.
ALTER TABLE procurement_batch_revisions ADD COLUMN source_row_id BIGINT NULL;
ALTER TABLE procurement_batch_revisions ADD CONSTRAINT uq_procurement_batch_source_row UNIQUE(store_id,source_row_id);
ALTER TABLE procurement_batch_revisions ADD CONSTRAINT fk_procurement_batch_source_row
 FOREIGN KEY(store_id,source_row_id) REFERENCES source_import_rows(store_id,id);

CREATE TABLE sales_order_revisions (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 store_id BIGINT NOT NULL,
 external_order_id VARCHAR(128) NOT NULL,
 revision BIGINT NOT NULL,
 source_row_id BIGINT NOT NULL,
 ordered_at DATETIME(6) NOT NULL,
 paid_at DATETIME(6) NOT NULL,
 status VARCHAR(32) NOT NULL,
 currency CHAR(3) NOT NULL,
 created_by BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(store_id,external_order_id,revision),
 UNIQUE(store_id,source_row_id),
 UNIQUE(store_id,id),
 CHECK(revision>0),
 FOREIGN KEY(store_id,source_row_id) REFERENCES source_import_rows(store_id,id),
 FOREIGN KEY(store_id) REFERENCES stores(id),
 FOREIGN KEY(created_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE sales_line_revisions (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 owner_id BIGINT NOT NULL,
 store_id BIGINT NOT NULL,
 external_line_id VARCHAR(128) NOT NULL,
 revision BIGINT NOT NULL,
 source_row_id BIGINT NOT NULL,
 order_revision_id BIGINT NOT NULL,
 source_sku VARCHAR(128) NOT NULL,
 mapping_version BIGINT NOT NULL,
 procurement_batch_id BIGINT NOT NULL,
 quantity DECIMAL(18,6) NOT NULL,
 unit_price DECIMAL(18,6) NOT NULL,
 discount_amount DECIMAL(18,6) NOT NULL,
 currency CHAR(3) NOT NULL,
 created_by BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(store_id,external_line_id,revision),
 UNIQUE(store_id,source_row_id),
 UNIQUE(store_id,id),
 CHECK(revision>0 AND quantity>0 AND unit_price>=0 AND discount_amount>=0 AND discount_amount<=quantity*unit_price),
 FOREIGN KEY(owner_id,store_id) REFERENCES stores(owner_id,id),
 FOREIGN KEY(store_id,source_row_id) REFERENCES source_import_rows(store_id,id),
 FOREIGN KEY(store_id,order_revision_id) REFERENCES sales_order_revisions(store_id,id),
 FOREIGN KEY(owner_id,store_id,source_sku,mapping_version) REFERENCES store_product_mapping_revisions(owner_id,store_id,source_sku,mapping_version),
 FOREIGN KEY(owner_id,store_id,procurement_batch_id) REFERENCES procurement_batch_revisions(owner_id,store_id,id),
 FOREIGN KEY(created_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE sales_refund_revisions (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 store_id BIGINT NOT NULL,
 external_refund_id VARCHAR(128) NOT NULL,
 revision BIGINT NOT NULL,
 source_row_id BIGINT NOT NULL,
 sales_line_revision_id BIGINT NOT NULL,
 confirmed_at DATETIME(6) NOT NULL,
 refund_amount DECIMAL(18,6) NOT NULL,
 currency CHAR(3) NOT NULL,
 returned_quantity DECIMAL(18,6) NOT NULL,
 restocked BOOLEAN NOT NULL,
 created_by BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(store_id,external_refund_id,revision),
 UNIQUE(store_id,source_row_id),
 CHECK(revision>0 AND refund_amount>=0 AND returned_quantity>=0),
 FOREIGN KEY(store_id,source_row_id) REFERENCES source_import_rows(store_id,id),
 FOREIGN KEY(store_id,sales_line_revision_id) REFERENCES sales_line_revisions(store_id,id),
 FOREIGN KEY(created_by) REFERENCES users(id)
) ENGINE=InnoDB;
