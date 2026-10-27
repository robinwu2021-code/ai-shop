-- 密钥模块 · 迁移 2：存储分档 + 显示前缀（TDD-支付服务-开放API加解密与密钥分发 §2.8 · 数据库设计 §3）。
-- 本片只交付基于数据库的两档（L2 库 + 注入主密钥、L3 库 + KMS 主密钥），存量版本都是 L2。
-- storage_ref 与放宽 ck_sec_key_version_1 留到 L1 / L4 / L5 落地的那一片：那时材料才第一次不在库里。

ALTER TABLE sec_key_version ADD COLUMN storage_tier VARCHAR(8) NOT NULL DEFAULT 'L2' COMMENT 'L1 配置明文 / L2 库 + 注入主密钥 / L3 库 + KMS 主密钥 / L4 外部密钥服务 / L5 KMS·HSM 不可导出；每个版本各自记录';
ALTER TABLE sec_key_version ADD COLUMN display_prefix VARCHAR(16) NULL COMMENT '只用于显示的前缀（AppSecret 的 sk_live_ / sk_test_）；不参与任何运算，不属于材料';
