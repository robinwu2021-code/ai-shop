-- No production points migration. Persist a cash send attempt before remote dispatch.
ALTER TABLE stl_payment ADD COLUMN refund_dispatch_at BIGINT DEFAULT NULL
    COMMENT 'Single refund send attempt timestamp - unknown results require original intent query';
