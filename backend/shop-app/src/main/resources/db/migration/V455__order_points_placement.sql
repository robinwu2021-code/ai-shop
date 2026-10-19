-- Recoverable source workflow, not balances and not a replacement order implementation.
CREATE TABLE IF NOT EXISTS ord_points_placement (
    order_no VARCHAR(64) NOT NULL,
    phase VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    payload_json LONGTEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(order_no),
    KEY idx_ord_points_placement_recovery(phase,updated_at,order_no)
);
