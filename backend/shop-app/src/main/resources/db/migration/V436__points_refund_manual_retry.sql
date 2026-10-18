-- A manually retried refund must have a new immutable after-sale/preparation identity.
CREATE TABLE IF NOT EXISTS ord_points_refund_retry (
    old_after_sale_no VARCHAR(64) NOT NULL,
    new_after_sale_no VARCHAR(64) NOT NULL,
    operator_no VARCHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (old_after_sale_no),
    UNIQUE KEY uk_ord_points_refund_retry_new (new_after_sale_no)
);
