-- Refunds integrated with points hold a pre-split bill until their cash/zero-cash proof is final.
-- No business balances are migrated; points integration remains disabled by default.
CREATE TABLE IF NOT EXISTS stl_points_refund_hold (
    after_sale_no VARCHAR(40) NOT NULL,
    settle_no VARCHAR(40) NOT NULL,
    sub_order_no VARCHAR(40) NOT NULL,
    goods_minor BIGINT NOT NULL,
    freight_minor BIGINT NOT NULL,
    goods_paid_minor BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    proof_no VARCHAR(64) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (after_sale_no),
    KEY idx_stl_points_refund_hold_bill (settle_no, status)
);
