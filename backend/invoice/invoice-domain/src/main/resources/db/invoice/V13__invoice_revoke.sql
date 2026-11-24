-- 注销与同号重开（TDD-发票-注销与同号重开 §2）：注销（MIG F0701，汎宇 C0701）之后号码可以用原号重开。
-- 唯一键从「号码唯一」改为「有效号码唯一」：开立 / 注销中 / 作废的票 active_fiscal_key = fiscal_number；
-- 已注销的票置空，同号重开的新票才插得进去。作废的票保留键 —— 作废后号码不能再用。
-- 状态加 REVOKING（注销已提交、等服务商流程）/ REVOKED（已注销）。

ALTER TABLE ivc_invoice
  ADD COLUMN active_fiscal_key VARCHAR(32) NULL COMMENT '有效号码键：开立 / 注销中 / 作废 = fiscal_number；已注销置空，号码可重开',
  ADD COLUMN revoke_reason VARCHAR(128) NULL COMMENT '注销原因',
  ADD COLUMN revoke_requested_at DATETIME(3) NULL COMMENT '提交注销的时间',
  ADD COLUMN revoked_at DATETIME(3) NULL COMMENT '服务商确认注销完成的时间',
  ADD COLUMN revoke_error VARCHAR(255) NULL COMMENT '最近一次注销失败原因（服务商流程失败或上传被拒）',
  ADD COLUMN revoke_alerted TINYINT NOT NULL DEFAULT 0 COMMENT '注销中超时 / 期末未重开已告警的级别：0 未告警，1 注销超时，2 未重开',
  ADD COLUMN revised_from_invoice_no VARCHAR(32) NULL COMMENT '本票是哪张注销票的同号重开',
  ADD COLUMN revised_to_invoice_no VARCHAR(32) NULL COMMENT '本票注销后重开成了哪张',
  ADD COLUMN blank_reported_at DATETIME NULL COMMENT '已注销未重开的号码，期末作空白号码上报的时间';

UPDATE ivc_invoice SET active_fiscal_key = fiscal_number;

ALTER TABLE ivc_invoice
  DROP INDEX uk_ivc_invoice_fiscal,
  ADD UNIQUE KEY uk_ivc_invoice_active_fiscal (tenant_no, regime_code, active_fiscal_key),
  ADD KEY idx_ivc_invoice_fiscal (tenant_no, regime_code, fiscal_number),
  ADD KEY idx_ivc_invoice_revoking (status, revoke_requested_at),
  MODIFY status VARCHAR(16) NOT NULL COMMENT 'ISSUED / VOIDED / REVOKING / REVOKED';

ALTER TABLE ivc_transmission
  MODIFY message_type VARCHAR(16) NOT NULL COMMENT 'ISSUE / VOID / ALLOWANCE / ALLOWANCE_VOID / REVOKE';
