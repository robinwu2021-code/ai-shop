-- Commit-order sequence, distinct from platform Outbox delivery id. No business migration.
CREATE TABLE IF NOT EXISTS ord_points_source_counter (
    type_code VARCHAR(64) NOT NULL,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY(type_code)
);
CREATE TABLE IF NOT EXISTS ord_points_source_fact (
    event_no VARCHAR(120) NOT NULL,
    type_code VARCHAR(64) NOT NULL,
    source_sequence BIGINT NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    sub_order_no VARCHAR(64) NOT NULL,
    schema_version INT NOT NULL,
    normalized_hash VARCHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    terminal_source TINYINT NOT NULL DEFAULT 0,
    delivered TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at DATETIME NULL,
    PRIMARY KEY(event_no),
    UNIQUE KEY uk_ord_points_source_sequence(type_code,source_sequence),
    KEY idx_ord_points_source_pending(delivered,created_at),
    KEY idx_ord_points_source_sub(sub_order_no,delivered,terminal_source),
    KEY idx_ord_points_source_store(store_no,delivered)
);
