-- Versioned consumer-benefit closure; legacy executions keep their original recovery semantics.
ALTER TABLE ord_refund_execution ADD COLUMN closure_version INT NOT NULL DEFAULT 1;
ALTER TABLE ord_refund_execution ADD COLUMN closure_status VARCHAR(16) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE ord_refund_execution ADD COLUMN return_coupon BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_refund_closure_recover ON ord_refund_execution(closure_status, next_attempt_at);
CREATE TABLE ord_refund_coupon_scope (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    coupon_no VARCHAR(64) NULL,
    payload_json MEDIUMTEXT NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_refund_coupon_scope_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ord_refund_obligation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    execution_no VARCHAR(64) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    source_no VARCHAR(64) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    payload_json MEDIUMTEXT NOT NULL,
    state VARCHAR(24) NOT NULL,
    authority_proof VARCHAR(128) NULL,
    error_code VARCHAR(128) NULL,
    next_attempt_at BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_refund_obligation_source (execution_no, kind, source_no),
    KEY idx_refund_obligation_recover (state, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
