-- Source refund reservations are separate from confirmed refunded counters.
CREATE TABLE ord_refund_execution (
    id BIGINT NOT NULL AUTO_INCREMENT,
    after_sale_no VARCHAR(64) NOT NULL,
    sub_order_no VARCHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    payload_json MEDIUMTEXT NOT NULL,
    cash_payment_no VARCHAR(64) DEFAULT NULL,
    net_cash_minor BIGINT DEFAULT NULL,
    result_json MEDIUMTEXT DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_execution_as (after_sale_no),
    UNIQUE KEY uk_refund_execution_cash (cash_payment_no),
    KEY ix_refund_execution_sub_state (sub_order_no,state)
) COMMENT='Immutable source refund preparation and execution phase';
