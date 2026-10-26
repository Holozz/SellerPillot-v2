CREATE TABLE action_proposals (
 id CHAR(32) PRIMARY KEY,
 store_id BIGINT NOT NULL,
 product_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 product_version BIGINT NOT NULL,
 original_title VARCHAR(200) NOT NULL,
 proposed_title VARCHAR(200) NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'pending',
 expires_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(store_id,product_id) REFERENCES products(store_id,id),
 FOREIGN KEY(user_id) REFERENCES users(id),
 CHECK(status IN ('pending','applied','rejected'))
) ENGINE=InnoDB;
CREATE TABLE action_executions (
 id CHAR(32) PRIMARY KEY,
 store_id BIGINT NOT NULL,
 proposal_id CHAR(32) NOT NULL UNIQUE,
 idempotency_key VARCHAR(100) NOT NULL,
 receipt JSON NOT NULL,
 UNIQUE(store_id,idempotency_key),
 FOREIGN KEY(store_id) REFERENCES stores(id),
 FOREIGN KEY(proposal_id) REFERENCES action_proposals(id)
) ENGINE=InnoDB;
CREATE TABLE audit_logs (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 store_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 action VARCHAR(40) NOT NULL,
 target VARCHAR(100) NOT NULL,
 detail JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(store_id) REFERENCES stores(id),
 FOREIGN KEY(user_id) REFERENCES users(id)
) ENGINE=InnoDB;
