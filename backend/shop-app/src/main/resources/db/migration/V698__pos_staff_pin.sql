CREATE TABLE mch_pos_staff_pin (
    tenant_no VARCHAR(32) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    subject_kind VARCHAR(16) NOT NULL,
    actor_no VARCHAR(64) NOT NULL,
    pin_hash VARCHAR(60) NOT NULL,
    failed_count INT NOT NULL DEFAULT 0,
    locked_until_ms BIGINT NULL,
    PRIMARY KEY (tenant_no, entity_no, store_no, subject_kind, actor_no)
);
