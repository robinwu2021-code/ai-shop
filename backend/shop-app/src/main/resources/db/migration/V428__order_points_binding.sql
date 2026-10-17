-- Frozen source participation. Not a points balance or a replacement pay ledger.
CREATE TABLE IF NOT EXISTS ord_points_binding (
    binding_no VARCHAR(40) NOT NULL,
    request_id VARCHAR(120) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    sub_order_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    phase VARCHAR(16) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    last_transition_json LONGTEXT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (binding_no),
    UNIQUE KEY uk_ord_points_request (request_id),
    UNIQUE KEY uk_ord_points_sub (sub_order_no),
    KEY idx_ord_points_open (store_no,phase),
    KEY idx_ord_points_recovery (phase,updated_at,binding_no)
);
