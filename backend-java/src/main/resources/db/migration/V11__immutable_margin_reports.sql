CREATE TABLE margin_reports (
 id CHAR(32) PRIMARY KEY,
 owner_id BIGINT NOT NULL,
 store_ids JSON NOT NULL,
 input_manifest JSON NOT NULL,
 result JSON NOT NULL,
 created_by BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(owner_id) REFERENCES users(id),
 FOREIGN KEY(created_by) REFERENCES users(id)
) ENGINE=InnoDB;
