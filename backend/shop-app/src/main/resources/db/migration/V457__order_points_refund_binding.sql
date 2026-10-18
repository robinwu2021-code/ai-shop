-- A source refund reserves its original points decision before any pay or bank action.
CREATE TABLE IF NOT EXISTS ord_points_refund_binding (
    after_sale_no VARCHAR(64) NOT NULL,
    sub_order_no VARCHAR(64) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    source_version BIGINT NOT NULL,
    command_json LONGTEXT NOT NULL,
    preparation_json LONGTEXT NULL,
    cash_payment_no VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (after_sale_no),
    KEY idx_ord_points_refund_pending (phase,updated_at,after_sale_no),
    KEY idx_ord_points_refund_sub (sub_order_no,phase)
);
