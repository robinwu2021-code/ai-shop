-- 密钥模块 · 迁移 3：只存摘要的存储档 DIGEST（TDD-基础能力库对齐 §2.2「1h 开放应用」，Q15）。
-- API Key 类凭据（运营接口密钥、库存开放凭证）进 sec_key 只存出示串的 SHA-256：版本上没有密文也没有公开材料，
-- 所以放宽 ck_sec_key_version_1（三者至少一个）。同一所有者可同时有多张有效凭据：DIGEST 版本各自 ACTIVE，active_key 留空。

ALTER TABLE sec_key_version ADD COLUMN material_digest CHAR(64) NULL COMMENT 'DIGEST 档：出示串（含显示前缀）UTF-8 的 SHA-256 小写十六进制；验证按它查，库里没有可用的凭据';
ALTER TABLE sec_key_version ADD COLUMN credential_key VARCHAR(120) NULL COMMENT 'DIGEST 档 = namespace|owner_type|external_key_id：同一命名空间同一归属类型下凭据号唯一';
ALTER TABLE sec_key_version ADD COLUMN label VARCHAR(80) NULL COMMENT 'DIGEST 档：凭据给人看的名字';
ALTER TABLE sec_key_version ADD COLUMN scope VARCHAR(255) NULL COMMENT 'DIGEST 档：调用方模块定义的权限范围（如 READ_ONLY / MANAGE），密钥模块不解释';
ALTER TABLE sec_key_version ADD UNIQUE KEY uk_sec_key_version_3 (material_digest);
ALTER TABLE sec_key_version ADD UNIQUE KEY uk_sec_key_version_4 (credential_key);
ALTER TABLE sec_key_version DROP CONSTRAINT ck_sec_key_version_1;
ALTER TABLE sec_key_version ADD CONSTRAINT ck_sec_key_version_1 CHECK (sec_secret_blob_id IS NOT NULL OR public_material IS NOT NULL OR material_digest IS NOT NULL);
