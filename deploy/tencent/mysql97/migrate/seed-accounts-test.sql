-- 测试环境的运营账号（ACCOUNTS 档 · 只在 --level test 时灌）
--
-- **口令是公开的，因为这就是测试账号**：Test@12345（全部 11 个相同）。
-- 正因为公开，这个文件**绝不能进生产**：build-fresh.sh 只在 --level test 时灌它，
-- 生产档（--level required）建出来的库里 sys_ops_staff 与 sys_role_member 都是 0 行。
--
-- 2026-09-16 整理的由来：这 11 个账号原先**只存在于生产那台机器的库里**，
-- 仓库里没有任何东西能重建它们 —— 换一台机器就没有运营账号可登录，
-- 而 sys_role_member（它们的角色绑定）却被归在「必要」档里跟着进生产，
-- 于是生产档会长出 11 条指向不存在账号的悬空绑定。
--
-- 生产环境怎么开第一个管理员：见 migrate/README「新环境怎么登进去」。
-- 不要把本文件的哈希复制过去 —— 那等于把一个公开口令装进生产。

USE `ai_shop`;
DELETE FROM sys_role_member WHERE end_code='OPS' AND subject_no LIKE 'ST-%';
DELETE FROM sys_ops_staff  WHERE staff_no LIKE 'ST-%';

INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-ADMIN', 'admin', '$2a$10$bGOLLPg/t.8y4OyPs8hgVOsDlgf47CXXjtSFaqElOiaTxxOSd.J32', '超级管理员', '["SUPER_ADMIN"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-ADMIN', 'SUPER_ADMIN', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-BD', 'bd', '$2a$10$bGOLLPg/t.8y4OyPs8hgVOsDlgf47CXXjtSFaqElOiaTxxOSd.J32', '商家运营', '["BD"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-BD', 'BD', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-GOODS', 'goods', '$2a$10$bGOLLPg/t.8y4OyPs8hgVOsDlgf47CXXjtSFaqElOiaTxxOSd.J32', '商品运营', '["GOODS_OPS"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-GOODS', 'GOODS_OPS', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-SUPPORT', 'support', '$2a$10$bGOLLPg/t.8y4OyPs8hgVOsDlgf47CXXjtSFaqElOiaTxxOSd.J32', '客服', '["SUPPORT"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-SUPPORT', 'SUPPORT', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-CAMPAIGN', 'campaign', '$2a$10$bGOLLPg/t.8y4OyPs8hgVOsDlgf47CXXjtSFaqElOiaTxxOSd.J32', '活动运营', '["CAMPAIGN_OPS"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-CAMPAIGN', 'CAMPAIGN_OPS', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-COMMUNITY', 'community', '$2a$10$bGOLLPg/t.8y4OyPs8hgVOsDlgf47CXXjtSFaqElOiaTxxOSd.J32', '社区运营', '["COMMUNITY_OPS"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-COMMUNITY', 'COMMUNITY_OPS', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-AUDITOR', 'auditor', '$2a$10$bGOLLPg/t.8y4OyPs8hgVOsDlgf47CXXjtSFaqElOiaTxxOSd.J32', '审核员', '["AUDITOR"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-AUDITOR', 'AUDITOR', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-FINANCE', 'finance', '$2a$10$bGOLLPg/t.8y4OyPs8hgVOsDlgf47CXXjtSFaqElOiaTxxOSd.J32', '财务', '["FINANCE"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-FINANCE', 'FINANCE', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-RISK', 'risk', '$2a$10$bGOLLPg/t.8y4OyPs8hgVOsDlgf47CXXjtSFaqElOiaTxxOSd.J32', '风控', '["RISK"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-RISK', 'RISK', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-ANALYST', 'analyst', '$2a$10$bGOLLPg/t.8y4OyPs8hgVOsDlgf47CXXjtSFaqElOiaTxxOSd.J32', '数据分析', '["ANALYST"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-ANALYST', 'ANALYST', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-TECHOPS', 'techops', '$2a$10$bGOLLPg/t.8y4OyPs8hgVOsDlgf47CXXjtSFaqElOiaTxxOSd.J32', '技术运维', '["TECH_OPS"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-TECHOPS', 'TECH_OPS', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
