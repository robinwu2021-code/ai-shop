-- 场景 × 通道 触达配置（设计：多渠道推送与运营端触达配置 · 需求 1）。
--
-- 把此前硬编码在 NotificationConsumer 的「哪个事件走哪些通道」搬进库，
-- 让运营端一个勾选面板可配 —— 改一条不用发版。
--
-- 三层语义：
--   1) INAPP（站内信）永远发、不可关，是事实记录（见 TDD 原则）。这里也落一行，
--      只为让运营界面把它显示成「已开·锁定」；代码里 NotificationConsumer 硬编码必发，
--      不读这张表的 INAPP 行 —— 配置表被误删/误关也不能让事实记录消失。
--   2) WXSUB（微信订阅消息）只在**代码有对应模板调用**的场景可配（到货、退款）；
--      其余场景没有 wx 模板，给它开关也发不出，故不落 WXSUB 行。
--   3) PUSH（App 推送）每个场景都可配；push_level 决定 NORMAL / RING。
--
-- ⚠️ channel 取值沿用 SysNotifyLog 常量 + INAPP；audience 取值见 MsgSceneChannel。
-- ⚠️ 种子行为与搬迁前**逐格一致**：默认行为一模一样，只是从硬编码变可配。
--    「App 也继承微信」= 到货/退款场景 WXSUB 与 PUSH 可同时开，运营按场景定，
--    不由「用户装没装 App」定。
--
-- 可重入（WHERE NOT EXISTS）：迁移重跑、本地库切分支都会让它再执行一次。
CREATE TABLE IF NOT EXISTS msg_scene_channel
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    scene_code VARCHAR(48) NOT NULL COMMENT '场景码（= 业务事件码，如 ORDER_ARRIVED）',
    audience VARCHAR(16) NOT NULL COMMENT '受众 C_USER / B_STAFF / OPS_STAFF',
    channel VARCHAR(16) NOT NULL COMMENT '通道 INAPP / WXSUB / PUSH / SMS',
    enabled TINYINT(4) NOT NULL DEFAULT 1 COMMENT '运营开关（INAPP 恒为 1，界面锁定）',
    push_level VARCHAR(8) NOT NULL DEFAULT 'NORMAL' COMMENT '推送级别 NORMAL / RING（仅 channel=PUSH 有意义）',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scene_channel (scene_code, audience, channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='场景×通道触达配置';

-- 种子：逐格照搬 NotificationConsumer 搬迁前的规则表。
-- 列：scene, audience, channel, enabled, push_level
INSERT INTO msg_scene_channel (scene_code, audience, channel, enabled, push_level, created_at, updated_at)
SELECT t.scene_code, t.audience, t.channel, t.enabled, t.push_level, NOW(), NOW()
FROM (
    -- C 端
    SELECT 'ORDER_PAID' AS scene_code, 'C_USER' AS audience, 'INAPP' AS channel, 1 AS enabled, 'NORMAL' AS push_level UNION ALL
    SELECT 'ORDER_PAID', 'C_USER', 'PUSH', 0, 'NORMAL' UNION ALL
    SELECT 'ORDER_ARRIVED', 'C_USER', 'INAPP', 1, 'NORMAL' UNION ALL
    SELECT 'ORDER_ARRIVED', 'C_USER', 'WXSUB', 1, 'NORMAL' UNION ALL
    SELECT 'ORDER_ARRIVED', 'C_USER', 'PUSH', 1, 'NORMAL' UNION ALL
    SELECT 'SUB_ORDER_COMPLETED', 'C_USER', 'INAPP', 1, 'NORMAL' UNION ALL
    SELECT 'SUB_ORDER_COMPLETED', 'C_USER', 'PUSH', 0, 'NORMAL' UNION ALL
    SELECT 'AFTER_SALE_REFUNDED', 'C_USER', 'INAPP', 1, 'NORMAL' UNION ALL
    SELECT 'AFTER_SALE_REFUNDED', 'C_USER', 'WXSUB', 1, 'NORMAL' UNION ALL
    SELECT 'AFTER_SALE_REFUNDED', 'C_USER', 'PUSH', 0, 'NORMAL' UNION ALL
    -- B 端
    SELECT 'SUB_ORDER_PAID', 'B_STAFF', 'INAPP', 1, 'NORMAL' UNION ALL
    SELECT 'SUB_ORDER_PAID', 'B_STAFF', 'PUSH', 1, 'RING' UNION ALL
    SELECT 'AFTER_SALE_APPLIED', 'B_STAFF', 'INAPP', 1, 'NORMAL' UNION ALL
    SELECT 'AFTER_SALE_APPLIED', 'B_STAFF', 'PUSH', 1, 'NORMAL' UNION ALL
    SELECT 'REVIEW_CREATED', 'B_STAFF', 'INAPP', 1, 'NORMAL' UNION ALL
    SELECT 'REVIEW_CREATED', 'B_STAFF', 'PUSH', 1, 'NORMAL'
) t
WHERE NOT EXISTS (
    SELECT 1 FROM msg_scene_channel m
    WHERE m.scene_code = t.scene_code AND m.audience = t.audience AND m.channel = t.channel
);
