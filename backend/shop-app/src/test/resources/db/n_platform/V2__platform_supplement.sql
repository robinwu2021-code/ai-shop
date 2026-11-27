-- 设计草案之外、代码需要的补充。同时是给 n_platform 的修改建议，尚未在任何正式库执行。
-- 依据：TDD-登录与账号对齐平台基础库 §2.4、TDD-平台基础库换表 §2。

-- 会话与票据：按令牌摘要查会话要求至多一行、并按它加行锁串行；票据核销要求恰好更新一行。
-- 同一份建表语句里 ops_session 是好的，这几张更像是生成时出的错。
ALTER TABLE `usr_session` DROP INDEX `uk_usr_session_token`;
ALTER TABLE `usr_session` ADD UNIQUE KEY `uk_usr_session_token_hash` (`token_hash`);
ALTER TABLE `mch_session` DROP INDEX `uk_mch_session_token`;
ALTER TABLE `mch_session` ADD UNIQUE KEY `uk_mch_session_token_hash` (`token_hash`);
ALTER TABLE `auth_ticket` DROP INDEX `uk_login_ticket_hash`;
ALTER TABLE `auth_ticket` ADD UNIQUE KEY `uk_auth_ticket_ticket_hash` (`ticket_hash`);
ALTER TABLE `usr_guest_session` ADD UNIQUE KEY `uk_usr_guest_session_token_hash` (`token_hash`);
ALTER TABLE `usr_guest_session` ADD UNIQUE KEY `uk_usr_guest_session_session_no` (`session_no`);

-- 租户号：代码插入时不给，靠库上的默认值 MAIN（旧表都有）。新表没有默认值，插入会失败或写成空。
ALTER TABLE `mch_group` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_entity` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_qualification` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_category_license` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_store` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_store_device` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_store_fulfillment` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_store_service_area` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_brand` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_brand_store_binding` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_personnel` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_personnel_store` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_personnel_position` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_account` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_pos_staff_pin` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `auth_ticket` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_role` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_role_point` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_grant` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `mch_store_end_grant` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `agr_agreement_version` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `agr_agreement_consent` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `usr_person` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `usr_account` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `usr_identity` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `usr_guest_session` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
ALTER TABLE `ops_staff` ALTER COLUMN `tenant_no` SET DEFAULT 'MAIN';
