-- 开票服务商（TDD-发票模块 §11）：鲸跃按商家账号认证、由厂商配号段；折让单号须符合 MIG 16 位上限。

ALTER TABLE ivc_regime_profile
  ADD COLUMN provider_account_code VARCHAR(64) NULL COMMENT '服务商侧的商家代号（鲸跃 officeid）',
  ADD COLUMN provider_secret_ref BIGINT NULL COMMENT 'secret 模块引用：服务商账号与密码（鲸跃 posno / posid），不存明文';

ALTER TABLE ivc_number_range
  ADD COLUMN allocation_owner VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL 商家登记 / PROVIDER 服务商配给',
  ADD COLUMN apply_id VARCHAR(64) NULL COMMENT '服务商配号申请号（鲸跃 applyid）',
  ADD COLUMN invoice_type VARCHAR(4) NULL COMMENT '服务商回传的发票种类，如 07';

ALTER TABLE ivc_allowance
  ADD COLUMN fiscal_allowance_number VARCHAR(16) NULL COMMENT '对外折让单号：发票号码 + 两位序号（MIG 上限 16 位）';
