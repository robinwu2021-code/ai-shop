-- 渠道注册表（设计：触达推送中台-模块抽象 · N2）。
--
-- 把「平台有哪些触达渠道、各自什么接入范围、开没开」从代码里的 @ConditionalOnProperty +
-- 环境变量，抬成一等实体。一条 = (通道类型 × 供应商 × 接入范围 × 归属)。
--
-- 三条边界（与既有原则一致）：
--   · 密钥永远不进这张表：只存 cred_ref(指向 env 前缀) 与非密 config_json（签名/模板号/topic）；
--   · status 不落列：它必须反映「凭据齐没齐 + 启停 + 体检」的实时事实，落列必与现实分叉，
--     由 NotifyChannelRegistry 读时派生；
--   · enabled 是软开关：运营点一下即时生效，不重启（桩开关仍在 env，是安全底线）。
--
-- owner_no NOT NULL DEFAULT ''：scope=MERCHANT 时才有值；空串而非 NULL，让唯一键对
-- 平台/测试渠道也能挡住重复（MariaDB 唯一键允许多个 NULL）。
CREATE TABLE IF NOT EXISTS notify_channel
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    channel_no VARCHAR(48) NOT NULL COMMENT '渠道编号（业务主键）',
    channel_type VARCHAR(16) NOT NULL COMMENT 'SMS / MAIL / WXSUB / PUSH / INAPP',
    provider VARCHAR(16) NOT NULL COMMENT 'ALI / SMTP / WECHAT / GETUI / FCM / APNS / INTERNAL',
    scope VARCHAR(16) NOT NULL COMMENT '接入范围 PLATFORM / MERCHANT / TEST',
    owner_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'scope=MERCHANT 的商家号；平台/测试为空串',
    enabled TINYINT(4) NOT NULL DEFAULT 1 COMMENT '软开关，运营即时启停',
    priority INT(11) NOT NULL DEFAULT 100 COMMENT '同类型同供应商多实例的选择优先级，小者先',
    cred_ref VARCHAR(64) DEFAULT NULL COMMENT '凭据引用（env 前缀），不存密钥明文',
    config_json VARCHAR(1024) NOT NULL DEFAULT '{}' COMMENT '非密参数（签名/模板号/topic 等），可回显',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notify_channel (channel_type, provider, scope, owner_no),
    UNIQUE KEY uk_notify_channel_no (channel_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='触达渠道注册表';

-- 种子：登记现有各通道。平台接入（PLATFORM）每个供应商一条；测试接入（TEST）对四条
-- 外部通道各一条（= 桩）。INAPP 只有平台一条（站内信无桩之说，恒写 msg_message）。
-- enabled 一律 1 = 现状「渠道都开着，只由 env 的 stub 决定真发还是走桩」，行为不变。
INSERT INTO notify_channel (channel_no, channel_type, provider, scope, cred_ref, created_at, updated_at)
SELECT t.channel_no, t.channel_type, t.provider, t.scope, t.cred_ref, NOW(), NOW()
FROM (
    SELECT 'NCH-SMS-ALI' AS channel_no, 'SMS' AS channel_type, 'ALI' AS provider, 'PLATFORM' AS scope, 'shop.sms.ali' AS cred_ref UNION ALL
    SELECT 'NCH-MAIL-SMTP', 'MAIL', 'SMTP', 'PLATFORM', 'shop.mail' UNION ALL
    SELECT 'NCH-WXSUB-WECHAT', 'WXSUB', 'WECHAT', 'PLATFORM', 'shop.wx' UNION ALL
    SELECT 'NCH-PUSH-GETUI', 'PUSH', 'GETUI', 'PLATFORM', 'shop.push.getui' UNION ALL
    SELECT 'NCH-PUSH-FCM', 'PUSH', 'FCM', 'PLATFORM', 'shop.push.fcm' UNION ALL
    SELECT 'NCH-PUSH-APNS', 'PUSH', 'APNS', 'PLATFORM', 'shop.push.apns' UNION ALL
    SELECT 'NCH-INAPP', 'INAPP', 'INTERNAL', 'PLATFORM', NULL UNION ALL
    SELECT 'NCH-SMS-ALI-TEST', 'SMS', 'ALI', 'TEST', NULL UNION ALL
    SELECT 'NCH-MAIL-SMTP-TEST', 'MAIL', 'SMTP', 'TEST', NULL UNION ALL
    SELECT 'NCH-WXSUB-WECHAT-TEST', 'WXSUB', 'WECHAT', 'TEST', NULL UNION ALL
    SELECT 'NCH-PUSH-GETUI-TEST', 'PUSH', 'GETUI', 'TEST', NULL
) t
WHERE NOT EXISTS (
    SELECT 1 FROM notify_channel m
    WHERE m.channel_type = t.channel_type AND m.provider = t.provider
      AND m.scope = t.scope AND m.owner_no = ''
);
