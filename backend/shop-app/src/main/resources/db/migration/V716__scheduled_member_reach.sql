ALTER TABLE mbr_reach_task
    ADD COLUMN delivery_status VARCHAR(16) NOT NULL DEFAULT 'SENT',
    ADD COLUMN scheduled_at BIGINT NULL,
    ADD COLUMN schedule_zone VARCHAR(64) NULL,
    ADD COLUMN request_key VARCHAR(64) NULL,
    ADD COLUMN request_hash CHAR(64) NULL,
    ADD UNIQUE KEY uk_mbr_reach_request (entity_no, request_key),
    ADD INDEX idx_mbr_reach_due (delivery_status, scheduled_at);

CREATE TABLE mbr_reach_recipient (
    task_no VARCHAR(64) NOT NULL,
    member_no VARCHAR(64) NOT NULL,
    outcome VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    reach_no VARCHAR(64) NULL,
    PRIMARY KEY (task_no, member_no),
    KEY idx_mbr_reach_recipient_pending (task_no, outcome, member_no)
) COMMENT='预约触达逐人结果，按到期名单续跑';
