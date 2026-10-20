-- Existing notify_push_task is a marketing broadcast queue and permits repeat RUNNING delivery.
-- This single-recipient ledger reserves authorization once and never replays uncertain sends.
CREATE TABLE notify_stored_value_task (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_no VARCHAR(32) NOT NULL,
    usage_no VARCHAR(64) NOT NULL,
    recipient VARCHAR(64) NOT NULL,
    scene VARCHAR(64) NOT NULL,
    event_no VARCHAR(128) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    document_no VARCHAR(64) NULL,
    values_json TEXT NOT NULL,
    open_id VARCHAR(128) NULL,
    template_id VARCHAR(128) NULL,
    status VARCHAR(24) NOT NULL,
    reason_code VARCHAR(64) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sv_notify_business (tenant_no, usage_no, recipient, scene),
    KEY idx_sv_notify_due (status, next_attempt_at),
    KEY idx_sv_notify_recovery (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO notify_scene_channel
    (tenant_no,scene_code,audience,channel,enabled,push_level,version,deleted,created_at,updated_at,created_by,updated_by)
VALUES
    ('MAIN','STORED_VALUE_REDEEMED','C_USER','WXSUB',0,'NORMAL',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'SYSTEM','SYSTEM'),
    ('MAIN','STORED_VALUE_REDEEMED','C_USER','INAPP',1,'NORMAL',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'SYSTEM','SYSTEM');
