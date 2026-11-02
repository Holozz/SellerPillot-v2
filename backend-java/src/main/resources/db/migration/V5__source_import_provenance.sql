-- Immutable source-file batches and row-level provenance for normalized imports.
CREATE TABLE source_import_batches (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 store_id BIGINT NOT NULL,
 kind VARCHAR(32) NOT NULL,
 file_hash CHAR(64) NOT NULL,
 source_file_key VARCHAR(512) NOT NULL,
 adapter_version VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'STAGING',
 source_row_count INT NOT NULL,
 created_by BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 published_at DATETIME(6) NULL,
 UNIQUE(store_id,kind,file_hash),
 UNIQUE(store_id,id),
 CHECK(kind IN ('orders','order-items','refunds','procurement-batches')),
 CHECK(status IN ('STAGING','PUBLISHED','REJECTED')),
 CHECK(source_row_count>=0),
 FOREIGN KEY(store_id) REFERENCES stores(id),
 FOREIGN KEY(created_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE source_import_rows (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 store_id BIGINT NOT NULL,
 batch_id BIGINT NOT NULL,
 source_row_number INT NOT NULL,
 source_record_key VARCHAR(256) NULL,
 source_record_hash CHAR(64) NOT NULL,
 raw_fields JSON NOT NULL,
 normalized_fields JSON NULL,
 validation_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 validation_errors JSON NULL,
 UNIQUE(batch_id,source_row_number),
 INDEX ix_source_rows_store_key(store_id,source_record_key),
 CHECK(source_row_number>0),
 CHECK(validation_status IN ('PENDING','VALID','INVALID')),
 FOREIGN KEY(store_id,batch_id) REFERENCES source_import_batches(store_id,id)
) ENGINE=InnoDB;
