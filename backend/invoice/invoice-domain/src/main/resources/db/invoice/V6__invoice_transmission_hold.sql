-- 上传失败分级止损（TDD-发票模块 §12）：账号认证失败时暂停该主体的上传，核验通过后恢复。
-- ivc_transmission.status 增加两个取值（列为 VARCHAR，无需改结构）：
--   HELD          账号问题暂停中，核验通过后回到 PENDING
--   NEEDS_REVIEW  报文被拒，不自动重发，待人工处理后手动重传

ALTER TABLE ivc_regime_profile
  ADD COLUMN provider_hold_reason VARCHAR(256) NULL COMMENT '上传暂停原因（服务商原文，如 cetustek invalid）；空 = 未暂停',
  ADD COLUMN provider_held_at DATETIME(3) NULL COMMENT '暂停时间（UTC）';

CREATE INDEX idx_ivc_transmission_scope_status ON ivc_transmission (tenant_no, entity_no, status);
