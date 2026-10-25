-- Append-only food reporting projections; report database only, never the business database.

CREATE TABLE rpt_scope_cursor (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    date_basis VARCHAR(24) NOT NULL DEFAULT 'BUSINESS_DATE',
    metric_version INT NOT NULL,
    first_stat_date DATE NOT NULL,
    last_planned_date DATE NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    default_for_reads BOOLEAN NOT NULL DEFAULT FALSE,
    read_slot TINYINT GENERATED ALWAYS AS (CASE WHEN default_for_reads THEN 1 ELSE NULL END) STORED,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
,
    UNIQUE KEY uk_rpt_scope_version (tenant_no,entity_no,store_no,date_basis,metric_version),
    UNIQUE KEY uk_rpt_scope_default (tenant_no,entity_no,store_no,date_basis,read_slot)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rpt_build_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    business_date DATE NOT NULL,
    date_basis VARCHAR(24) NOT NULL DEFAULT 'BUSINESS_DATE',
    metric_version INT NOT NULL,
    build_no VARCHAR(32) NOT NULL,
    actor_no VARCHAR(64) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    reason VARCHAR(24) NOT NULL,
    job_run_id VARCHAR(64) NULL,
    attempt_no BIGINT NOT NULL,
    fence BIGINT NOT NULL,
    expected_generation BIGINT NOT NULL,
    lease_owner VARCHAR(64) NOT NULL,
    lease_until DATETIME(6) NULL,
    status VARCHAR(24) NOT NULL,
    spec_hash CHAR(64) NOT NULL,
    required_sources JSON NOT NULL,
    day_evidence JSON NOT NULL,
    captured_dirty_generation BIGINT NOT NULL,
    validation_hash CHAR(64) NULL,
    failure_kind VARCHAR(32) NULL,
    error_code VARCHAR(64) NULL,
    next_retry_at DATETIME(6) NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
,
    UNIQUE KEY uk_rpt_build_no (build_no),
    UNIQUE KEY uk_rpt_build_request (tenant_no,entity_no,store_no,request_key),
    KEY idx_rpt_build_due (status,next_retry_at,lease_until),
    KEY idx_rpt_build_partition (tenant_no,entity_no,store_no,business_date,metric_version),
    KEY idx_rpt_build_run (job_run_id)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rpt_source_manifest (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    build_no VARCHAR(32) NOT NULL,
    attempt_no BIGINT NOT NULL,
    owner_code VARCHAR(24) NOT NULL,
    family_code VARCHAR(48) NOT NULL,
    source_snapshot_ref VARCHAR(128) NULL,
    source_version VARCHAR(128) NULL,
    as_of_at DATETIME(6) NOT NULL,
    row_count BIGINT NOT NULL,
    checksum CHAR(64) NULL,
    completeness VARCHAR(24) NOT NULL,
    unknown_reason VARCHAR(64) NULL,
    source_evidence JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
,
    UNIQUE KEY uk_rpt_manifest (build_no,attempt_no,owner_code,family_code)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rpt_day_publication (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    business_date DATE NOT NULL,
    date_basis VARCHAR(24) NOT NULL DEFAULT 'BUSINESS_DATE',
    metric_version INT NOT NULL,
    active_build_no VARCHAR(32) NULL,
    active_attempt_no BIGINT NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    current_fence BIGINT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(64) NULL,
    lease_until DATETIME(6) NULL,
    published_at DATETIME(6) NULL,
    coverage_summary JSON NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
,
    UNIQUE KEY uk_rpt_day_partition (tenant_no,entity_no,store_no,business_date,date_basis,metric_version)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rpt_dirty_day (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    business_date DATE NOT NULL,
    date_basis VARCHAR(24) NOT NULL DEFAULT 'BUSINESS_DATE',
    metric_version INT NOT NULL,
    dirty_generation BIGINT NOT NULL DEFAULT 0,
    acked_generation BIGINT NOT NULL DEFAULT 0,
    first_event_ref VARCHAR(128) NULL,
    last_event_ref VARCHAR(128) NULL,
    reason VARCHAR(48) NOT NULL,
    ready_after DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
,
    UNIQUE KEY uk_rpt_dirty_partition (tenant_no,entity_no,store_no,business_date,date_basis,metric_version),
    KEY idx_rpt_dirty_due (ready_after,dirty_generation,acked_generation)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rpt_food_metric_daily (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    business_date DATE NOT NULL,
    date_basis VARCHAR(24) NOT NULL DEFAULT 'BUSINESS_DATE',
    metric_version INT NOT NULL,
    build_no VARCHAR(32) NOT NULL,
    attempt_no BIGINT NOT NULL,
    aggregation_set VARCHAR(48) NOT NULL,
    metric_family VARCHAR(48) NOT NULL,
    metric_code VARCHAR(64) NOT NULL,
    attribution_basis VARCHAR(32) NOT NULL,
    dimension_key CHAR(64) NOT NULL,
    dimensions JSON NOT NULL,
    display_snapshot JSON NOT NULL,
    value_type VARCHAR(16) NOT NULL,
    count_value BIGINT NULL,
    quantity_value DECIMAL(24,6) NULL,
    amount_minor BIGINT NULL,
    currency VARCHAR(8) NULL,
    unit VARCHAR(32) NULL,
    completeness VARCHAR(24) NOT NULL,
    unknown_reason VARCHAR(64) NULL,
    known_count_value BIGINT NULL,
    known_quantity_value DECIMAL(24,6) NULL,
    known_amount_minor BIGINT NULL,
    sku_no VARCHAR(64) NULL,
    menu_no VARCHAR(64) NULL,
    channel_code VARCHAR(48) NULL,
    station_no VARCHAR(64) NULL,
    material_sku_no VARCHAR(64) NULL,
    actor_no VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
,
    UNIQUE KEY uk_rpt_metric (build_no,attempt_no,metric_code,dimension_key),
    KEY idx_rpt_metric_read (tenant_no,entity_no,store_no,business_date,metric_version,aggregation_set,metric_code),
    KEY idx_rpt_metric_sku (build_no,attempt_no,aggregation_set,metric_code,sku_no),
    CONSTRAINT ck_rpt_metric_value CHECK (
       (completeness IN ('COMPLETE','EMPTY_VERIFIED') AND
          ((value_type IN ('COUNT','DURATION') AND count_value IS NOT NULL AND quantity_value IS NULL AND amount_minor IS NULL)
           OR (value_type='QUANTITY' AND quantity_value IS NOT NULL AND count_value IS NULL AND amount_minor IS NULL AND unit IS NOT NULL)
           OR (value_type='MONEY' AND amount_minor IS NOT NULL AND count_value IS NULL AND quantity_value IS NULL AND currency IS NOT NULL)))
       OR (completeness IN ('PARTIAL','UNKNOWN') AND count_value IS NULL AND quantity_value IS NULL
           AND amount_minor IS NULL AND unknown_reason IS NOT NULL))

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
