-- Local v2 baseline for the offline-payment slice; legacy commerce migrations are independent.
CREATE TABLE mall_offline_payment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_no VARCHAR(32) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    action_no VARCHAR(64) NOT NULL,
    record_type VARCHAR(16) NOT NULL,
    claim_no VARCHAR(64),
    kind VARCHAR(16),
    currency VARCHAR(8),
    amount_minor BIGINT,
    transferred_at BIGINT,
    operator_no VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    review_no VARCHAR(64),
    review_note VARCHAR(1024),
    confirmed_at BIGINT,
    payment_no VARCHAR(64),
    failure_code VARCHAR(64),
    action_snapshot JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    -- Buyers and operators choose identifiers in independent namespaces.
    UNIQUE KEY uk_offline_record (tenant_no,order_no,record_type,action_no),
    KEY ix_offline_queue (tenant_no,record_type,status,order_no,action_no)
) ENGINE=InnoDB;

CREATE TABLE mall_offline_evidence (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_no VARCHAR(32) NOT NULL,
    asset_key VARCHAR(512) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    action_no VARCHAR(64),
    submitter_no VARCHAR(64) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    scope_snapshot JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE KEY uk_offline_asset (tenant_no,asset_key),
    KEY ix_offline_evidence_order (tenant_no,order_no,action_no)
) ENGINE=InnoDB;
