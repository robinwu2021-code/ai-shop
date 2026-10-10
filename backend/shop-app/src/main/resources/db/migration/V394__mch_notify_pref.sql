-- =====================================================================
-- V394 商家自己的通知开关（TDD-来单四渠道与商家通知设置 §2.1）
--
-- 来单这一条四条腿同时走：微信订阅 / 企微群 / 短信 / App 推送。
-- 四个开关由店主在 B 端一个页面上管 —— 此前只有平台级的 notify_scene_channel
-- （那是运营的总闸，商家碰不到），于是「别给我发短信」这种诉求无处表达。
--
-- 粒度 = **门店** × 场景 × 通道（用户 2026-10-10 订正：开关和设置基于门店）。
--
-- 为什么是门店而不是主体：自营这一个主体下已经有 4 家店（鲜果 / 福田 / 粮油 / 测试店），
-- 各店的人不同、各店可以有自己的企微群 —— 「粮油店的来单别往鲜果的群里发」
-- 在主体粒度下根本表达不了。
--
-- ⚠️ **这个粒度只管得到来单**：SubOrderPaid 事件带 store_no，而
--    AfterSaleApplied / ReviewCreated **不带**（它们只有 entity_no）。
--    所以售后与评价仍然只走平台总闸 notify_scene_channel。
--    要把它们也纳进来，得先给那两个事件加 store_no —— 那是另一件事。
--
-- 开关仍是**店主/店长设、整店生效**：微信订阅与 App 推送本来按人（各自授权、各自设备），
-- 企微群是整店一个，短信只发店主 —— 四者的收件人粒度本就不同，
-- 开关若也按人，店主就看不到「这家店到底有没有人能收到来单」这件事了。
--
-- ⚠️ **缺行 = 开，不是关。** 这是整张表最要紧的语义：
--    反过来的话，这张表一建，所有存量商家当天就一条来单提醒都收不到，
--    而症状是「没有消息」—— 没有报错、没有日志，商家只会以为最近没单。
--    所以**不写种子**：种子等于把「默认」写死两遍，两处迟早分叉。
--
-- ⚠️ **INAPP 不进这张表**：站内信是事实记录，不给开关（与场景×通道里
--    INAPP 不可关同理）。守在后端，前端被绕过也兜住。
-- =====================================================================

CREATE TABLE IF NOT EXISTS mch_notify_pref
(
    id         BIGINT(20)  NOT NULL AUTO_INCREMENT,
    store_no   VARCHAR(64) NOT NULL COMMENT '门店号。粒度是门店不是主体 —— 一个主体下多家店各管各的',
    scene      VARCHAR(48) NOT NULL COMMENT '场景码，如 SUB_ORDER_PAID（与 NotifyScene 同一套）',
    channel    VARCHAR(16) NOT NULL COMMENT 'WXSUB 微信订阅 / WEBHOOK 企微群 / SMS 短信 / MAIL 邮件 / PUSH App 推送。INAPP 不在此表',
    enabled    TINYINT(4)  NOT NULL DEFAULT 1 COMMENT '商家自己的开关。与平台 notify_scene_channel 串联：平台关了这里开也不发',
    tenant_no  VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME    NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME    NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version    BIGINT(20)  NOT NULL DEFAULT 0,
    deleted    TINYINT(4)  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mch_notify_pref (store_no, scene, channel, tenant_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='门店自己的通知开关（缺行=开）';

-- ---------------------------------------------------------------------
-- 平台总闸补两行：来单的 SMS 与 WEBHOOK（B_STAFF × SUB_ORDER_PAID）。
--
-- 没有这两行，SceneChannelRouting「查不到 = 关」会把它们关死 ——
-- 而那条兜底是为「新场景别擅自外发」设的，不是为「这条通道不存在」设的。
--
-- **SMS 这行开着，而阿里云的来单模板还没报备下来。** 刻意如此：
-- 缺模板时 sendOrderPaid 会写一行 SMS/FAILED/tpl_unconfigured ——
-- 那正是「为什么没发短信」的可见证据。设成 0 看起来像「不想发」，
-- 而真相是「还发不了」，两件事在排查时差别很大。模板填上那一刻就通，不用改这张表。
-- ---------------------------------------------------------------------

INSERT INTO notify_scene_channel (scene_code, audience, channel, enabled, push_level,
                                  tenant_no, created_at, updated_at)
-- push_level 是 NOT NULL DEFAULT 'NORMAL'（V156），只对 channel=PUSH 有意义；
-- 这两行照其余非 PUSH 行的口径填 'NORMAL'，不是 NULL
SELECT t.scene_code, t.audience, t.channel, 1, 'NORMAL', 'MAIN', NOW(), NOW()
FROM (SELECT 'SUB_ORDER_PAID' AS scene_code, 'B_STAFF' AS audience, 'SMS' AS channel
      UNION ALL
      SELECT 'SUB_ORDER_PAID', 'B_STAFF', 'WEBHOOK') t
WHERE NOT EXISTS (SELECT 1
                  FROM notify_scene_channel x
                  WHERE x.scene_code = t.scene_code
                    AND x.audience = t.audience
                    AND x.channel = t.channel);
