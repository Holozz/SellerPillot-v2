CREATE TABLE latest_margin_reports (
    scope_key CHAR(64) PRIMARY KEY,
    report_id CHAR(32) NOT NULL,
    FOREIGN KEY (report_id) REFERENCES margin_reports(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TEMPORARY TABLE margin_report_scope_backfill (
    id CHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    scope_key CHAR(64) NOT NULL
);

INSERT INTO margin_report_scope_backfill(id,created_at,scope_key)
    SELECT id,created_at,SHA2(CONCAT_WS('|',
        REPLACE(CAST(input_manifest->'$.store_ids' AS CHAR),' ',''),
        input_manifest->>'$.start',
        input_manifest->>'$.end',
        input_manifest->>'$.fx_rate_set.id',
        COALESCE(NULLIF(input_manifest->>'$.fixed_rate_date','null'),'')),256) AS scope_key
    FROM margin_reports;

INSERT INTO latest_margin_reports(scope_key,report_id)
WITH ranked AS (
    SELECT id,scope_key,ROW_NUMBER() OVER(PARTITION BY scope_key ORDER BY created_at DESC,id DESC) AS position_in_scope
    FROM margin_report_scope_backfill
)
SELECT scope_key,id FROM ranked WHERE position_in_scope=1;

DROP TEMPORARY TABLE margin_report_scope_backfill;
