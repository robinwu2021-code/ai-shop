-- 测试身份白名单（TDD-测试身份白名单登录）：手机号或邮箱命中后用统一验证码 / 统一密码登录、注册。
--
-- 与 usr_otp_test_phone（苹果审核号，每号单独设码、只认大陆号）并存，互不影响。
-- 统一码与统一密码**不进表**：只在服务器配置 shop.auth.test-identity.code / .password，总开关
-- shop.auth.test-identity.enabled 默认关；prod profile 下打开即拒绝启动（TestIdentityGuard）。
-- 不灌种子数据：由运营端「系统 › 测试身份」维护，带审计；上限 10 条启用。
CREATE TABLE IF NOT EXISTS usr_test_identity
(
    id             BIGINT(20)  NOT NULL AUTO_INCREMENT,
    identity_type  VARCHAR(16) NOT NULL COMMENT 'PHONE / EMAIL',
    identity_value VARCHAR(128) NOT NULL COMMENT '规范化后的值：手机号去空格与连字符（如 +886900000102），邮箱小写',
    enabled        TINYINT(4)  NOT NULL DEFAULT 1 COMMENT '停用即时生效',
    remark         VARCHAR(128) DEFAULT NULL COMMENT '这一条为什么存在',
    tenant_no      VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at     DATETIME    NOT NULL,
    created_by     VARCHAR(64) DEFAULT NULL,
    updated_at     DATETIME    NOT NULL,
    updated_by     VARCHAR(64) DEFAULT NULL,
    version        BIGINT(20)  NOT NULL DEFAULT 0,
    deleted        TINYINT(4)  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_test_identity (identity_type, identity_value)
) COMMENT='测试身份白名单（统一验证码 / 统一密码，联调期用）';
