-- 测试环境的运营账号（ACCOUNTS 档 · 只在 --level test 时灌）
--
-- **口令规则与 DevSeeder 逐字一致：`<用户名>123`**（admin123 / bd123 / support123 …）。
-- 那是仓库里既有的约定，246 处测试调用点在用它（TestLogin.admin 等）。
-- 2026-09-16 本文件第一版另立了一套统一口令，那是错的 —— 同一批账号两套口令并存，
-- 下一个人不知道该信哪个。以既有约定为准。
--
-- 两个种子器的分工：
--   DevSeeder（应用启动时）—— 跑测试用。它要求**空库**（判据是 cmt_community 有没有数据），
--                            所以 build-fresh 的测试档灌完业务数据之后它会整段跳过。
--   本文件（build-fresh --level test）—— 建**独立测试环境**用，不经过应用启动。
--
-- 口令是公开的，因为这就是测试账号；也正因为公开，生产档（--level required）不灌它。
-- 生产环境怎么开第一个管理员：见 migrate/README「新环境怎么登进去」。

USE `ai_shop`;
DELETE FROM sys_role_member WHERE end_code='OPS' AND subject_no LIKE 'ST-%';
DELETE FROM sys_ops_staff  WHERE staff_no LIKE 'ST-%';

-- admin / admin123
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-ADMIN', 'admin', '$2a$10$8XPBrM0bQ50Trgex2gYKD.7v7zw9TWDI.2ZGPZmu.k9F7R9wF13mO', '超级管理员', '["SUPER_ADMIN"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-ADMIN', 'SUPER_ADMIN', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
-- bd / bd123
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-BD', 'bd', '$2a$10$1syjbLq5BIMmats0BeEYG.vOSuAchPkmS3B1s6ZE8QG1msOD2sPFW', '商家运营', '["BD"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-BD', 'BD', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
-- goods / goods123
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-GOODS', 'goods', '$2a$10$PfCajhkhOkmXmTwz16kd3OSwD.JSZi22cE7V5KW5/FAkOR7h6Rvly', '商品运营', '["GOODS_OPS"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-GOODS', 'GOODS_OPS', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
-- support / support123
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-SUPPORT', 'support', '$2a$10$ltLKINUmWkCjMJCPWomf1ulvt5ENuPAnxsqEIPqTI9hpX885S/m/u', '客服', '["SUPPORT"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-SUPPORT', 'SUPPORT', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
-- campaign / campaign123
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-CAMPAIGN', 'campaign', '$2a$10$7Q46EaHGyQlT.bIE07Pe4eMiHEKWCaQkuhIXfwv/guWlc6jMeQ5PG', '活动运营', '["CAMPAIGN_OPS"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-CAMPAIGN', 'CAMPAIGN_OPS', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
-- community / community123
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-COMMUNITY', 'community', '$2a$10$gymtwJa6mBNKRdKBJIjQVOTsI82iexpLkvlRWzjgbtoV1yyvDOgcm', '社区运营', '["COMMUNITY_OPS"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-COMMUNITY', 'COMMUNITY_OPS', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
-- auditor / auditor123
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-AUDITOR', 'auditor', '$2a$10$g.a/.Yur8pgfks3/Sr213OKp9v2k7a2EH6BKTltVbvL9.KayZIA0K', '审核员', '["AUDITOR"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-AUDITOR', 'AUDITOR', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
-- finance / finance123
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-FINANCE', 'finance', '$2a$10$M7Bb0Xsxv./kVau3fs6V4e8wILFYwNpZqh5b/14AaudSfq36IL9uu', '财务', '["FINANCE"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-FINANCE', 'FINANCE', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
-- risk / risk123
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-RISK', 'risk', '$2a$10$WS/FMRGfrlpfwsAiISbN3OFcpeQvbXFRRzLUij6c2Jv9lEDxIVJuq', '风控', '["RISK"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-RISK', 'RISK', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
-- analyst / analyst123
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-ANALYST', 'analyst', '$2a$10$pSzpTOkz.ilk0o8J8S1eteDAPRwXw47AIVeQTzrT4bU0qyC2L6i2.', '数据分析', '["ANALYST"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-ANALYST', 'ANALYST', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
-- techops / techops123
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted, must_change_password)
VALUES ('ST-TECHOPS', 'techops', '$2a$10$pvY/VLzlZjf279Uv./masOgMUwCVnp8aks4Hw71sBQzXEWcoECMGK', '技术运维', '["TECH_OPS"]', 'ACTIVE', 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0, 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES ('OPS', 'ST-TECHOPS', 'TECH_OPS', UNIX_TIMESTAMP()*1000, 'MAIN', NOW(), 'SEED', NOW(), 'SEED', 0, 0);
