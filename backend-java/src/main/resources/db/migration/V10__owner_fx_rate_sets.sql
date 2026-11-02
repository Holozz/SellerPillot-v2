ALTER TABLE fx_rate_sets ADD COLUMN owner_id BIGINT NULL;
ALTER TABLE fx_rate_sets ADD CONSTRAINT fk_fx_owner FOREIGN KEY(owner_id) REFERENCES users(id);
ALTER TABLE fx_rate_sets DROP INDEX source;
ALTER TABLE fx_rate_sets ADD CONSTRAINT uq_fx_owner_revision UNIQUE(owner_id,source,source_revision);
