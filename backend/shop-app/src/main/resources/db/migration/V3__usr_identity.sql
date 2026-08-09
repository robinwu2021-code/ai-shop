-- 账号与凭证分离（模块优化实施步骤 S2 / 安全整改方案 §六）。
--
-- 解决的问题：usr_account 把 phone/openid/apple_sub 平铺成三个列，登录时只按本次用的
-- 那一列查，查不到就建新账号。于是同一个人在小程序登录建账号①、在 App 用手机号登录
-- 建账号②，订单/积分/卡包全部分裂，且**不报任何错**。
--
-- 更硬的限制：单列唯一键意味着一个账号只能有一个 openid，而微信 openid 是按应用隔离的
-- ——现在的表结构根本存不下「同一个人从小程序和 App 都登录过」这个事实。
--
-- **本次只加不删**：usr_account 的四个凭证列与三个唯一键全部保留，代码双写。
-- 验证一段时间、确认 usr_identity 与旧列一致后，再单独发一版删列。
-- 迁移与删列放在同一版的话，出问题就没有回退路径了。

CREATE TABLE IF NOT EXISTS usr_identity
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_no VARCHAR(64) NOT NULL COMMENT '归属的人',
    identity_type VARCHAR(32) NOT NULL COMMENT 'PHONE｜WX_UNIONID｜WX_OPENID_MP｜WX_OPENID_APP｜WX_OPENID_OA｜APPLE_SUB',
    identity_value VARCHAR(191) NOT NULL COMMENT '凭证值。191 是 utf8mb4 下唯一索引的长度上限',
    channel VARCHAR(16) DEFAULT NULL COMMENT '来源留痕：MP｜APP｜H5',
    verified_at DATETIME DEFAULT NULL COMMENT '验证时间。手机号 OTP 通过即记，微信凭证由平台背书',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 一个凭证只能属于一个人。这条唯一键是「不会静默产生两个账号」的最终保证：
    -- 代码里的冲突检测可能有漏，这里是数据库层面的兜底
    UNIQUE KEY uk_identity (identity_type, identity_value),
    KEY idx_user_no (user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户登录凭证。一个人多条，新增来源只是多一行，不再改表';

-- 回填：把 usr_account 现有的四个凭证列各拆成一行。
-- 存量的 openid 一律按小程序算 —— 目前只有小程序这一个来源在写它。
INSERT INTO usr_identity (user_no, identity_type, identity_value, channel, verified_at,
                          tenant_no, created_at, updated_at)
SELECT user_no, 'PHONE', phone, NULL, created_at, tenant_no, created_at, created_at
FROM usr_account WHERE phone IS NOT NULL AND phone <> '' AND deleted = 0;

INSERT INTO usr_identity (user_no, identity_type, identity_value, channel, verified_at,
                          tenant_no, created_at, updated_at)
SELECT user_no, 'WX_OPENID_MP', openid, 'MP', created_at, tenant_no, created_at, created_at
FROM usr_account WHERE openid IS NOT NULL AND openid <> '' AND deleted = 0;

INSERT INTO usr_identity (user_no, identity_type, identity_value, channel, verified_at,
                          tenant_no, created_at, updated_at)
SELECT user_no, 'WX_UNIONID', unionid, NULL, created_at, tenant_no, created_at, created_at
FROM usr_account WHERE unionid IS NOT NULL AND unionid <> '' AND deleted = 0;

INSERT INTO usr_identity (user_no, identity_type, identity_value, channel, verified_at,
                          tenant_no, created_at, updated_at)
SELECT user_no, 'APPLE_SUB', apple_sub, 'APP', created_at, tenant_no, created_at, created_at
FROM usr_account WHERE apple_sub IS NOT NULL AND apple_sub <> '' AND deleted = 0;
