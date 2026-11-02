-- Versioned owner-scoped SKU mappings and imported procurement lots.
ALTER TABLE stores ADD CONSTRAINT uq_stores_owner_id UNIQUE(owner_id,id);
ALTER TABLE source_import_rows ADD CONSTRAINT uq_source_rows_store_id UNIQUE(store_id,id);

CREATE TABLE catalog_products (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 owner_id BIGINT NOT NULL,
 internal_sku VARCHAR(64) NOT NULL,
 title VARCHAR(200) NOT NULL,
 base_unit VARCHAR(24) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(owner_id,internal_sku),
 UNIQUE(owner_id,id),
 FOREIGN KEY(owner_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE store_product_mapping_revisions (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 owner_id BIGINT NOT NULL,
 store_id BIGINT NOT NULL,
 source_sku VARCHAR(128) NOT NULL,
 mapping_version BIGINT NOT NULL,
 catalog_product_id BIGINT NOT NULL,
 created_by BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(store_id,source_sku,mapping_version),
 UNIQUE(owner_id,store_id,source_sku,mapping_version),
 UNIQUE(owner_id,store_id,id),
 CHECK(mapping_version>0),
 FOREIGN KEY(owner_id,store_id) REFERENCES stores(owner_id,id),
 FOREIGN KEY(owner_id,catalog_product_id) REFERENCES catalog_products(owner_id,id),
 FOREIGN KEY(created_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE procurement_batch_revisions (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 owner_id BIGINT NOT NULL,
 store_id BIGINT NOT NULL,
 source_batch_id VARCHAR(128) NOT NULL,
 batch_revision BIGINT NOT NULL,
 source_sku VARCHAR(128) NOT NULL,
 mapping_version BIGINT NOT NULL,
 received_at DATETIME(6) NOT NULL,
 quantity DECIMAL(18,6) NOT NULL,
 unit_purchase_cost DECIMAL(18,6) NOT NULL,
 currency CHAR(3) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(store_id,source_batch_id,batch_revision),
 UNIQUE(owner_id,store_id,id),
 CHECK(batch_revision>0 AND quantity>0 AND unit_purchase_cost>=0),
 FOREIGN KEY(owner_id,store_id,source_sku,mapping_version)
   REFERENCES store_product_mapping_revisions(owner_id,store_id,source_sku,mapping_version)
) ENGINE=InnoDB;

CREATE TABLE procurement_batch_allocations (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 owner_id BIGINT NOT NULL,
 store_id BIGINT NOT NULL,
 source_row_id BIGINT NOT NULL,
 procurement_batch_id BIGINT NOT NULL,
 quantity DECIMAL(18,6) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(source_row_id,procurement_batch_id),
 FOREIGN KEY(store_id,source_row_id) REFERENCES source_import_rows(store_id,id),
 FOREIGN KEY(owner_id,store_id,procurement_batch_id) REFERENCES procurement_batch_revisions(owner_id,store_id,id),
 CHECK(quantity>0)
) ENGINE=InnoDB;
