ALTER TABLE pay_ent_exception_action
 ADD COLUMN operation_kind VARCHAR(32) NOT NULL DEFAULT 'RETRY_GRANT' COMMENT 'RETRY_GRANT/QUERY_SOURCE',
 ADD CONSTRAINT ck_exception_action_kind CHECK (operation_kind IN ('RETRY_GRANT','QUERY_SOURCE'));
