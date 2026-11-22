-- 折让作废（TDD-发票模块 §13）：折让单有自己的作废状态；作废后累计折让回退，报文 G0501 上传。

ALTER TABLE ivc_allowance
  ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / VOIDED',
  ADD COLUMN void_reason VARCHAR(256) NULL COMMENT '作废原因',
  ADD COLUMN voided_at DATETIME(3) NULL COMMENT '作废时间（UTC）',
  ADD COLUMN voided_by VARCHAR(64) NULL COMMENT '作废人';
