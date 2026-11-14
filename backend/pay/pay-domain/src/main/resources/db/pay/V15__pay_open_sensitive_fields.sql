-- 支付服务 v4 · 迁移 15：开放 API 敏感字段落库（开发计划 M3.5；TDD-支付服务-开放API加解密与密钥分发 §2.7：
-- 「解出的敏感字段若要落库，立即经密钥模块重新加密存 sec_secret_blob，业务表只存 blob id 与掩码」）。
-- 每组字段一个封存（明文 JSON 进 sec_secret_blob），一列掩码 JSON 供查询直接回给接入方。

ALTER TABLE pay_order ADD COLUMN payer_blob_id BIGINT NULL
  COMMENT '付款人敏感信息（手机 / 邮箱 / 姓名，开放 API 建单传入）封存在 sec_secret_blob.id；没传为空';
ALTER TABLE pay_order ADD COLUMN payer_masked VARCHAR(255) NULL
  COMMENT '付款人敏感信息的掩码 JSON，如 {"phone":"1380****5678"}；查询只回它';

ALTER TABLE pay_refund ADD COLUMN refund_account_blob_id BIGINT NULL
  COMMENT '线下退款收款账号（银行代码 / 账号 / 户名）封存在 sec_secret_blob.id；没传为空';
ALTER TABLE pay_refund ADD COLUMN refund_account_masked VARCHAR(255) NULL
  COMMENT '收款账号的掩码 JSON，如 {"accountNo":"8123****4567"}；查询只回它';
