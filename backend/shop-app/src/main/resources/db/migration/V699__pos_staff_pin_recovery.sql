CREATE TABLE mch_pos_pin_recovery (
    tenant_no VARCHAR(32) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    subject_kind VARCHAR(16) NOT NULL,
    actor_no VARCHAR(64) NOT NULL,
    method VARCHAR(16) NOT NULL,
    challenge_id CHAR(36) NOT NULL,
    session_hash CHAR(64) NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    reset_hash CHAR(64) NULL,
    reset_expires_at_ms BIGINT NULL,
    consumed_at_ms BIGINT NULL,
    PRIMARY KEY (tenant_no, entity_no, store_no, subject_kind, actor_no, method),
    UNIQUE (challenge_id),
    UNIQUE (reset_hash)
);
