-- 支付服务 v4 · 迁移 14：开放 API 加解密与密钥分发的列（开发计划 M3.8；TDD-支付服务-开放API加解密-数据库设计 §4，
-- 原排 V17 —— db/pay 号须从 1 连续，先合先占）。密钥本身不在这里：登记在 secret 模块（sec_key / sec_key_version）。
-- 开发者控制台三张表（pay_app_developer / pay_app_activation / pay_app_key_challenge）随控制台另出，不在本迁移。

ALTER TABLE pay_app ADD COLUMN sign_type VARCHAR(16) NOT NULL DEFAULT 'HMAC'
  COMMENT 'HMAC 基础方案（AppSecret）/ RSA 增强方案（接入方私钥 + 平台证书）；一个 app 只能一种';

ALTER TABLE pay_biz_notify ADD COLUMN enc_key_version INT NULL
  COMMENT 'WEBHOOK：加密 resource 用的 EncryptKey 版本（sec_key_version.version_index）；重推一律用它';
ALTER TABLE pay_biz_notify ADD COLUMN sign_key_version INT NULL
  COMMENT 'WEBHOOK：签名用的密钥版本（HMAC 为 AppSecret，RSA 为平台签名密钥）；重推一律用它';

ALTER TABLE pay_api_request_log ADD COLUMN sign_key_version INT NULL
  COMMENT '验签命中的密钥版本；未通过为空。统计「还有谁在用旧版本」靠它';
ALTER TABLE pay_api_request_log ADD COLUMN encrypt_serial VARCHAR(64) NULL
  COMMENT '请求头 X-Encrypt-Serial（有加密字段时）';
