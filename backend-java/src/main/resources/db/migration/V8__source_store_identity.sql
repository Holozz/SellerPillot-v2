-- Bind imported rows to the configured source store, not only the URL store id.
ALTER TABLE stores ADD COLUMN source_store_key VARCHAR(128) NULL;
UPDATE stores SET source_store_key=name;
ALTER TABLE stores MODIFY source_store_key VARCHAR(128) NOT NULL;
ALTER TABLE stores ADD CONSTRAINT uq_stores_owner_platform_source_key
 UNIQUE(owner_id,platform,source_store_key);
ALTER TABLE source_import_batches ADD COLUMN source_file MEDIUMBLOB NULL;
