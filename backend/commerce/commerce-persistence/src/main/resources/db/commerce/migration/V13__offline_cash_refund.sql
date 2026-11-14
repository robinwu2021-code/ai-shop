-- Business refund coordination only; stl_payment remains the sole money authority.
CREATE TABLE mall_offline_cash_refund (
    tenant_no VARCHAR(32) NOT NULL,
    refund_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    payment_no VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    snapshot_json JSON NOT NULL,
    active_payment_no VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status IN ('REFUNDED','REJECTED') THEN NULL ELSE payment_no END) STORED,
    PRIMARY KEY (tenant_no, refund_no),
    UNIQUE KEY uk_cash_refund_active (tenant_no, active_payment_no),
    KEY idx_cash_refund_order (tenant_no, order_no),
    KEY idx_cash_refund_pending (tenant_no, status, refund_no)
);
