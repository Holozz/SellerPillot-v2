CREATE TABLE margin_job_queue (
    id INT PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO margin_job_queue VALUES (1);

CREATE TABLE margin_report_jobs (
    id CHAR(32) PRIMARY KEY,
    request_key CHAR(64) NOT NULL UNIQUE,
    requested_by BIGINT NOT NULL,
    query_json JSON NOT NULL,
    store_versions JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    error_message VARCHAR(255),
    queued_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX ix_margin_job_queue (status, queued_at, id)
) ENGINE=InnoDB;
