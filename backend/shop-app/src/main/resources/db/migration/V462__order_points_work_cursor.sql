-- Round-robin recovery positions; no financial or business data initialization.
CREATE TABLE IF NOT EXISTS ord_points_work_cursor (
    queue_name VARCHAR(32) NOT NULL,
    after_reference VARCHAR(200) NOT NULL DEFAULT '',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(queue_name)
);
