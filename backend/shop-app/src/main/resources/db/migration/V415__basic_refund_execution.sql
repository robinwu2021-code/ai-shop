-- Cash is confirmed independently from resource restoration; frozen plans never change on replay.
CREATE TABLE ord_refund_execution (
    id BIGINT NOT NULL AUTO_INCREMENT,
    execution_no VARCHAR(64) NOT NULL,
    after_sale_no VARCHAR(64) NOT NULL,
    sub_order_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    origin_payment_no VARCHAR(64) NULL,
    pay_channel VARCHAR(32) NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(8) NOT NULL,
    plan_json JSON NOT NULL,
    stock_needs_json JSON NOT NULL,
    stock_authority VARCHAR(16) NULL,
    restore_stock BOOLEAN NOT NULL DEFAULT FALSE,
    restock_approved BOOLEAN NOT NULL DEFAULT FALSE,
    zero_liability BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(500) NULL,
    status VARCHAR(32) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    payment_no VARCHAR(64) NULL,
    next_attempt_at BIGINT NOT NULL,
    error_code VARCHAR(64) NULL,
    resource_status VARCHAR(32) NOT NULL,
    resource_error_code VARCHAR(64) NULL,
    succeeded_at BIGINT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ord_refund_execution (execution_no),
    UNIQUE KEY uk_ord_refund_after_sale (after_sale_no),
    KEY idx_ord_refund_recover (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE ord_after_sale ADD COLUMN restock_approved BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ord_after_sale ADD COLUMN return_received_at BIGINT NULL;
ALTER TABLE stl_payment ADD COLUMN refund_execution_no VARCHAR(64) NULL;
ALTER TABLE stl_payment ADD COLUMN origin_payment_no VARCHAR(64) NULL;
CREATE UNIQUE INDEX uk_stl_refund_execution ON stl_payment (refund_execution_no);
CREATE TABLE prd_stock_restore (
    id BIGINT NOT NULL AUTO_INCREMENT,
    purpose_no VARCHAR(64) NOT NULL,
    original_lock_id BIGINT NOT NULL,
    qty INT NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_prd_restore_purpose_source (purpose_no, original_lock_id),
    KEY idx_prd_restore_source (original_lock_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
