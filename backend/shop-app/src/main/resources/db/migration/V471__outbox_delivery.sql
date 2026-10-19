-- Unified Outbox: additive migration; legacy rows remain unadopted.
ALTER TABLE sys_outbox ADD COLUMN source_system VARCHAR(32);
ALTER TABLE sys_outbox ADD COLUMN source_event_no VARCHAR(64);
ALTER TABLE sys_outbox ADD COLUMN schema_version INT;
ALTER TABLE sys_outbox ADD COLUMN payload_hash VARCHAR(64);
ALTER TABLE sys_outbox ADD COLUMN stream_key VARCHAR(128);
ALTER TABLE sys_outbox ADD COLUMN stream_sequence BIGINT;
ALTER TABLE sys_outbox ADD COLUMN routing_mode VARCHAR(24);
ALTER TABLE sys_outbox ADD COLUMN occurred_at DATETIME;
CREATE UNIQUE INDEX uk_sys_outbox_source ON sys_outbox (source_system, source_event_no);
CREATE UNIQUE INDEX uk_sys_outbox_sequence ON sys_outbox (stream_key, stream_sequence);
CREATE TABLE sys_outbox_stream (
    stream_key VARCHAR(128) NOT NULL PRIMARY KEY,
    next_sequence BIGINT NOT NULL
);
CREATE TABLE sys_outbox_delivery (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_no VARCHAR(64) NOT NULL,
    handler_id VARCHAR(128) NOT NULL,
    stream_key VARCHAR(128) NOT NULL,
    stream_sequence BIGINT NOT NULL,
    external_workflow TINYINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    round_attempt_count INT NOT NULL DEFAULT 0,
    replay_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME,
    claim_token VARCHAR(64),
    lease_until DATETIME,
    first_attempt_at DATETIME,
    last_attempt_at DATETIME,
    last_replayed_at DATETIME,
    completed_at DATETIME,
    last_error_code VARCHAR(64),
    last_error VARCHAR(512),
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_sys_delivery_target (event_no, handler_id),
    KEY idx_sys_delivery_due (status, next_retry_at, id),
    KEY idx_sys_delivery_stream (handler_id, stream_key, stream_sequence),
    KEY idx_sys_delivery_lease (status, lease_until)
);
CREATE TABLE sys_outbox_operation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    event_no VARCHAR(64) NOT NULL,
    handler_id VARCHAR(128) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    original_error_code VARCHAR(64),
    original_error VARCHAR(512),
    original_attempt_count INT,
    original_round_attempt_count INT,
    original_last_attempt_at DATETIME,
    original_next_retry_at DATETIME,
    before_status VARCHAR(16) NOT NULL,
    after_status VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_sys_outbox_operation (request_id)
);
