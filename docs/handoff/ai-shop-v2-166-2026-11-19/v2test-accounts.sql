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

-- ---------------------------------------------------------------------------------------------
-- 2026-11-19 追加（用户：「可以在生产上建测试账号进行测试」）
-- 测试店员用户（SQL 写入）：
--   INSERT INTO usr_account (user_no, nickname, status, tenant_no, created_at, created_by, updated_at, updated_by, market)
--   VALUES ('UUMV2TESTCLERK0000000001', '【网关验证】测试店员', 'NORMAL', 'MAIN', NOW(), 'gateway-v2-verify', NOW(), 'gateway-v2-verify', 'TW');
-- 测试主体、门店、店员是经接口建的（不是 SQL）：
--   主体 M261119TS0PN0XBK9（POST /biz/merchant/quick-start，待补证照，买家不可见）
--   门店 ST261119BE70D8B9C9「【网关验证】测试店·请勿下单」
--   店主成员 SF261119RG23M66QX8 → UUMV2TESTOWNER0000000001
--   店员成员 SF2611194QFZKVW2WE（POST /biz/staff，登记电话 0900000991；角色 CLERK）
-- 店员成员绑定到测试用户（SQL，仅这一行）：
--   UPDATE mch_account SET user_no='UUMV2TESTCLERK0000000001', updated_by='gateway-v2-verify'
--    WHERE entity_no='M261119TS0PN0XBK9' AND is_owner=0 AND user_no IS NULL;
--
-- 清理测试主体与门店：**不要直接删表里的行**。快速开店会在多张表里建数据（主体、门店、成员、角色授权、
-- 默认设置等），门店还会被现有版本同步到支付系统。稳妥的做法是在运营端把这家店停用/归档，
-- 支付系统里的对应门店由支付侧处理。只有账号（usr_account、sys_ops_staff）可以按上面的语句直接删，
-- 且要先确认主体已经归档。
