-- =====================================================================
-- V395 门店的通知收件地址（TDD-来单四渠道与商家通知设置 §2.5）
--
-- 用户 2026-10-10：「邮箱，企业微信，短信都需要可以输入对应的接受地址，
-- 短信默认是登录手机号，可以增加最多两个」「都放到一张表，短信、邮箱地址、
-- webhook 都是单独的一列，短信用逗号分开，可存多个，代码限制不超过两个」。
--
-- **一个门店一行**，三条通道各一列。
--
-- 为什么不塞进 notify_channel.config_json（第一版的想法）：那个列是
-- VARCHAR(1024) 的自由 JSON，建出来到今天生产上 12 行全是 '{}' ——
-- 没有列级校验、查不了（「哪些店配了邮箱」要全表扫加解析）、
-- 改一个要读-改-写整串（并发下会把另一个会话刚加的那个覆盖掉），
-- 而手机号与邮箱是个人信息，混在自由 JSON 里将来要清理或留痕没有下手的地方。
--
-- ⚠️ **三列都存明文，webhook 也是**（用户 2026-10-10：「webhook 存明文即可，
--    加密将来再考虑」）。记下这个决定的代价，将来要回头时有依据：
--    拿到这一列的人就能往那个群发消息 —— 它与手机号/邮箱不同，后两者是「发给谁」，
--    它是「凭谁的名义发」。库被读走 = 一批商家的群发权限。
--    要改回加密时，notify_channel.secret_cipher 那套（NotifyCredCipher，AES-256-GCM，
--    密钥 SHOP_NOTIFY_CRED_KEY 2026-10-09 已配在生产 env）是现成的，
--    改动面是这一列 + MerchantNotifyRecipients 的读写两处。
--
-- ⚠️ **sms_phones 里不含店主的登录手机号**：那一个恒发、删不掉，
--    真源是 mch_account.login_phone。把它冗余进来的话，店主改了登录号
--    这里就是个过期的号，而症状是「短信发到旧号上」—— 没有任何报错。
--    这一列存的是**额外**的，代码限制最多 2 个（服务层判，见 MerchantNotifyRecipients）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS mch_notify_recipient
(
    id             BIGINT(20)    NOT NULL AUTO_INCREMENT,
    store_no       VARCHAR(64)   NOT NULL COMMENT '门店号。一个门店一行',
    sms_phones     VARCHAR(255)  DEFAULT NULL COMMENT '额外的短信接收号，逗号分隔，最多 2 个。店主登录手机号恒发，不在这里',
    email          VARCHAR(128)  DEFAULT NULL COMMENT '邮件接收地址。空 = 不发邮件',
    wecom_webhook  VARCHAR(512)  DEFAULT NULL COMMENT '企微群 Webhook 地址（明文，2026-10-10 决定；加密将来再议）',
    tenant_no      VARCHAR(32)   NOT NULL DEFAULT 'MAIN',
    created_at     DATETIME      NOT NULL,
    created_by     VARCHAR(64)   DEFAULT NULL,
    updated_at     DATETIME      NOT NULL,
    updated_by     VARCHAR(64)   DEFAULT NULL,
    version        BIGINT(20)    NOT NULL DEFAULT 0,
    deleted        TINYINT(4)    NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mch_notify_recipient (store_no, tenant_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='门店的通知收件地址（短信 / 邮件 / 企微群）';

-- ---------------------------------------------------------------------
-- 平台总闸补 MAIL 一行（B_STAFF × SUB_ORDER_PAID / AFTER_SALE_APPLIED / REVIEW_CREATED）。
--
-- 邮件是五条通道里**唯一一条生产上即开即用**的：MAIL_HOST / USERNAME /
-- PASSWORD / FROM 四项齐、SHOP_MAIL_STUB=false。短信反倒卡在阿里云模板报备。
--
-- 没有这几行，SceneChannelRouting「查不到 = 关」会把邮件关死。
-- ---------------------------------------------------------------------

INSERT INTO notify_scene_channel (scene_code, audience, channel, enabled, push_level,
                                  tenant_no, created_at, updated_at)
SELECT t.scene_code, 'B_STAFF', 'MAIL', 1, 'NORMAL', 'MAIN', NOW(), NOW()
FROM (SELECT 'SUB_ORDER_PAID' AS scene_code
      UNION ALL SELECT 'AFTER_SALE_APPLIED'
      UNION ALL SELECT 'REVIEW_CREATED') t
WHERE NOT EXISTS (SELECT 1
                  FROM notify_scene_channel x
                  WHERE x.scene_code = t.scene_code
                    AND x.audience = 'B_STAFF'
                    AND x.channel = 'MAIL');

-- 售后与评价也补齐 SMS / WEBHOOK —— V394 只给来单补了那两行，
-- 而三个场景的开关都已经在设置页上了：少了总闸的那两行，店主开了也不发。
INSERT INTO notify_scene_channel (scene_code, audience, channel, enabled, push_level,
                                  tenant_no, created_at, updated_at)
SELECT t.scene_code, 'B_STAFF', t.channel, 1, 'NORMAL', 'MAIN', NOW(), NOW()
FROM (SELECT 'AFTER_SALE_APPLIED' AS scene_code, 'SMS' AS channel
      UNION ALL SELECT 'AFTER_SALE_APPLIED', 'WEBHOOK'
      UNION ALL SELECT 'REVIEW_CREATED', 'SMS'
      UNION ALL SELECT 'REVIEW_CREATED', 'WEBHOOK') t
WHERE NOT EXISTS (SELECT 1
                  FROM notify_scene_channel x
                  WHERE x.scene_code = t.scene_code
                    AND x.audience = 'B_STAFF'
                    AND x.channel = t.channel);
