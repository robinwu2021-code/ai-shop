-- A source-owned scan position survives worker restart; no balance or order migration.
CREATE TABLE IF NOT EXISTS ord_points_recovery_cursor (
    queue_name VARCHAR(32) NOT NULL,
    after_sub_order_no VARCHAR(64) NOT NULL DEFAULT '',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(queue_name)
);
