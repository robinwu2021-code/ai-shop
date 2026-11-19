-- ai-shop v2 网关验证用的测试账号（2026-11-19，用户指示：自动写入测试账号做接口验证）。
-- 只新增行。口令是一段无人知道的随机散列，只能经密钥票据（ADR-027）登录。
-- 清理：见文末注释。
START TRANSACTION;
INSERT INTO sys_ops_staff (staff_no, username, password, real_name, roles, status, tenant_no, created_at, created_by, updated_at, updated_by, second_factor_exempt)
VALUES
 ('ST-V2TEST-ADMIN', 'v2test-admin', CONCAT('$2b$12$', LEFT(REPLACE(REPLACE(REPLACE(TO_BASE64(RANDOM_BYTES(60)),'+','.'),'=',''),'\n',''),53)), '【网关验证】测试管理员', '["SUPER_ADMIN"]', 'ACTIVE', 'MAIN', NOW(), 'gateway-v2-verify', NOW(), 'gateway-v2-verify', 0),
 ('ST-V2TEST-SUPPORT', 'v2test-support', CONCAT('$2b$12$', LEFT(REPLACE(REPLACE(REPLACE(TO_BASE64(RANDOM_BYTES(60)),'+','.'),'=',''),'\n',''),53)), '【网关验证】测试客服', '["SUPPORT"]', 'ACTIVE', 'MAIN', NOW(), 'gateway-v2-verify', NOW(), 'gateway-v2-verify', 0);
INSERT INTO sys_role_member (end_code, subject_no, role_code, scope_no, granted_by, granted_at, tenant_no, created_at, created_by, updated_at, updated_by)
VALUES
 ('OPS', 'ST-V2TEST-ADMIN', 'SUPER_ADMIN', NULL, 'gateway-v2-verify', NOW(), 'MAIN', NOW(), 'gateway-v2-verify', NOW(), 'gateway-v2-verify'),
 ('OPS', 'ST-V2TEST-SUPPORT', 'SUPPORT', NULL, 'gateway-v2-verify', NOW(), 'MAIN', NOW(), 'gateway-v2-verify', NOW(), 'gateway-v2-verify');
INSERT INTO usr_account (user_no, nickname, status, tenant_no, created_at, created_by, updated_at, updated_by, market)
VALUES
 ('UUMV2TESTBUYER0000000001', '【网关验证】测试买家', 'NORMAL', 'MAIN', NOW(), 'gateway-v2-verify', NOW(), 'gateway-v2-verify', 'TW'),
 ('UUMV2TESTOWNER0000000001', '【网关验证】测试店主', 'NORMAL', 'MAIN', NOW(), 'gateway-v2-verify', NOW(), 'gateway-v2-verify', 'TW');
COMMIT;
SELECT staff_no, roles, status, LENGTH(password) FROM sys_ops_staff WHERE staff_no LIKE 'ST-V2TEST%';
SELECT subject_no, role_code FROM sys_role_member WHERE subject_no LIKE 'ST-V2TEST%';
SELECT user_no, nickname, market FROM usr_account WHERE user_no LIKE 'UUMV2TEST%';
-- 清理：
-- DELETE FROM sys_role_member WHERE subject_no LIKE 'ST-V2TEST%';
-- DELETE FROM sys_ops_staff WHERE staff_no LIKE 'ST-V2TEST%';
-- DELETE FROM usr_account WHERE user_no LIKE 'UUMV2TEST%';
