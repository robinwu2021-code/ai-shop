-- Pay owns manual receipt claims and durable cancellation. Existing received promises remain owed.
ALTER TABLE sv_recharge
    ADD COLUMN received_receipt_key CHAR(64) NULL COMMENT '实收后占用的收款方式加凭据摘要',
    ADD COLUMN cancellation_reason VARCHAR(500) NULL COMMENT '未实收取消原因',
    ADD COLUMN cancelled_at DATETIME(3) NULL COMMENT '未实收取消时间',
    ADD UNIQUE KEY uk_sv_received_receipt (tenant_no,entity_no,received_receipt_key),
    ADD CONSTRAINT ck_sv_cancel_facts CHECK
        ((status='CANCELLED' AND cancellation_reason IS NOT NULL AND cancelled_at IS NOT NULL)
        OR (status<>'CANCELLED' AND cancelled_at IS NULL));
