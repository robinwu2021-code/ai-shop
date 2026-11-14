CREATE TABLE mch_management_command (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 command_id VARCHAR(64) NOT NULL,
 tenant_no VARCHAR(32) NOT NULL,
 aggregate_id VARCHAR(128) NOT NULL,
 payload JSON NOT NULL,
 created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_management_command(command_id),
 KEY ix_management_command_audit(tenant_no,aggregate_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Preserve original command identity, complete audit payload and pagination IDs.
INSERT INTO mch_management_command(id,command_id,tenant_no,aggregate_id,payload,created_at)
SELECT id,COALESCE(source_event_no,event_no),JSON_UNQUOTE(JSON_EXTRACT(payload,'$.tenantNo')),
 aggregate_id,CAST(payload AS JSON),created_at
FROM sys_outbox WHERE aggregate_type='MANAGEMENT' AND event_type='MANAGEMENT_CHANGED';

-- No business side effect was attached to this empty consumer. The original facts are above.
-- SKIPPED explicitly retires delivery; it does not fabricate a successful receipt.
UPDATE sys_outbox_delivery d JOIN sys_outbox o ON o.event_no=d.event_no
 JOIN mch_management_command c ON c.command_id=COALESCE(o.source_event_no,o.event_no)
SET d.status='SKIPPED',d.last_error_code='MANAGEMENT_JOURNAL_MOVED',
 d.last_error='Original management command retained in mch_management_command',
 d.claim_token=NULL,d.lease_until=NULL,d.completed_at=UTC_TIMESTAMP()
WHERE d.handler_id='management-audit-v1' AND d.status NOT IN ('SENT','WARNING','SKIPPED')
 AND o.aggregate_type='MANAGEMENT' AND o.event_type='MANAGEMENT_CHANGED';

UPDATE sys_outbox o JOIN mch_management_command c
 ON c.command_id=COALESCE(o.source_event_no,o.event_no)
SET o.status=CASE WHEN o.source_system IS NULL THEN 'ARCHIVED' ELSE 'SKIPPED' END,
 o.last_error='Management command and audit retained in mch_management_command'
WHERE o.aggregate_type='MANAGEMENT' AND o.event_type='MANAGEMENT_CHANGED'
 AND o.status NOT IN ('SENT','WARNING','SKIPPED','ARCHIVED')
 AND NOT EXISTS (SELECT 1 FROM sys_outbox_delivery d WHERE d.event_no=o.event_no
                 AND d.status NOT IN ('SENT','WARNING','SKIPPED'));
