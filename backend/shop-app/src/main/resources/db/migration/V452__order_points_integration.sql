-- Source-owned policy and idempotent audit; no business balance migration.
CREATE TABLE IF NOT EXISTS ord_points_integration_policy (
    store_no VARCHAR(64) NOT NULL,
    scene VARCHAR(16) NOT NULL,
    mode VARCHAR(16) NOT NULL DEFAULT 'DETACHED',
    payload_json LONGTEXT NULL,
    version BIGINT NOT NULL DEFAULT -1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (store_no,scene)
);
CREATE TABLE IF NOT EXISTS ord_points_command (
    request_id VARCHAR(120) NOT NULL,
    command_json LONGTEXT NOT NULL,
    result_json LONGTEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id)
);
