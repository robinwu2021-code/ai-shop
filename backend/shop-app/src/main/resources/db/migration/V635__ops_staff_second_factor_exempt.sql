-- 运营员工「免二次验证」白名单（员工账号页逐个勾选）。
--
-- 第二因子（shop.ops.auth.second-factor=email）整体保持开着 —— 生产 profile 不许整体关（OpsSecondFactorGuard）。
-- 个别账号（测试号、自动化账号）需要只凭密码登录时，由有 iam:staff:update 的人在员工账号页勾选：
-- 必须写理由、不能给自己勾，每次变更与每次免验证登录都进操作审计。默认 0 = 照旧要邮件验证码。
ALTER TABLE sys_ops_staff ADD COLUMN second_factor_exempt TINYINT NOT NULL DEFAULT 0;
