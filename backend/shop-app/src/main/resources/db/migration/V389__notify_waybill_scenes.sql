-- 快递节点通知的场景×通道种子（TDD-物流模块 批 4 / AC5）。
--
-- 不加这一行的后果是**静默**：路由「查不到 = 关」，通知永不外发（同 V364 的说明）。
--
-- 只给线下付款单发（运单 profile = SELF，判断在 NotificationConsumer）：
-- 微信支付单的物流动态由微信「购物订单」自己推，再发是重复打扰。
--
--   · INAPP 开：事实类通知，站内信是必达记录（SceneChannelSeedTest 要求）；
--   · PUSH  开、NORMAL：与发货那条同口径，不把人震醒去接一个还在路上的包裹；
--   · WXSUB 开：线下单在微信里能收到的只有这一路。模板号没配时发送端静默跳过，
--     开着不会出错 —— 关着的话，哪天配上了模板也发不出去，而那时没人会想起这一行。

INSERT INTO notify_scene_channel (scene_code, audience, channel, enabled, push_level, created_at, updated_at)
SELECT t.scene_code, t.audience, t.channel, t.enabled, t.push_level, NOW(), NOW()
FROM (
    SELECT 'WAYBILL_PROGRESSED' AS scene_code, 'C_USER' AS audience, 'INAPP' AS channel, 1 AS enabled, 'NORMAL' AS push_level UNION ALL
    SELECT 'WAYBILL_PROGRESSED', 'C_USER', 'PUSH', 1, 'NORMAL' UNION ALL
    SELECT 'WAYBILL_PROGRESSED', 'C_USER', 'WXSUB', 1, 'NORMAL' UNION ALL
    SELECT 'WAYBILL_SIGNED', 'C_USER', 'INAPP', 1, 'NORMAL' UNION ALL
    SELECT 'WAYBILL_SIGNED', 'C_USER', 'PUSH', 1, 'NORMAL' UNION ALL
    SELECT 'WAYBILL_SIGNED', 'C_USER', 'WXSUB', 1, 'NORMAL'
) t
WHERE NOT EXISTS (
    SELECT 1 FROM notify_scene_channel m
    WHERE m.scene_code = t.scene_code AND m.audience = t.audience AND m.channel = t.channel
);
