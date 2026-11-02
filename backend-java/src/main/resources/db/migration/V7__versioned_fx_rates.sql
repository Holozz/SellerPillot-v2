-- Immutable exchange-rate sets make every RMB report reproducible.
CREATE TABLE fx_rate_sets (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 source VARCHAR(100) NOT NULL,
 source_revision VARCHAR(64) NOT NULL,
 retrieved_at DATETIME(6) NOT NULL,
 created_by BIGINT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(source,source_revision),
 FOREIGN KEY(created_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE fx_rates (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 rate_set_id BIGINT NOT NULL,
 base_currency CHAR(3) NOT NULL,
 quote_currency CHAR(3) NOT NULL,
 effective_date DATE NOT NULL,
 rate DECIMAL(20,12) NOT NULL,
 published_at DATETIME(6) NULL,
 UNIQUE(rate_set_id,base_currency,quote_currency,effective_date),
 INDEX ix_fx_lookup(base_currency,quote_currency,effective_date),
 CHECK(rate>0),
 CHECK(base_currency<>quote_currency),
 FOREIGN KEY(rate_set_id) REFERENCES fx_rate_sets(id)
) ENGINE=InnoDB;
