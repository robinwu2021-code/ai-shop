-- 已发货 / 开始配送的场景×通道种子（TDD-通知与消息推送 §8.8）。
--
-- 不加这一行的后果是**静默**：路由「查不到 = 关」，通知永不外发，
-- 而代码、事件、消费分支全都在，看起来像做完了（NotifyScene 的类注释记着这个坑）。
--
-- INAPP + PUSH 都开：
--   · 这是**事实**不是营销 —— 货出门了、人在路上，买家必须知道；
--   · PUSH 用 NORMAL 不用 RING：买家不该被震醒去接一个还在路上的包裹
--     （与到货那条同口径，见 §6 三期 3.5）。
--
-- **不开 WXSUB**：微信那一路由「购物（实体物流）/（自提）服务动态」承担，
-- 商家调 upload_shipping_info 之后微信自己推（见 §8.4b）。
-- 我们再发一条订阅消息是重复的，而且要额外消耗用户的一次性授权额度。

INSERT INTO notify_scene_channel (scene_code, audience, channel, enabled, push_level, created_at, updated_at)
SELECT t.scene_code, t.audience, t.channel, t.enabled, t.push_level, NOW(), NOW()
FROM (
    SELECT 'SUB_ORDER_SHIPPED' AS scene_code, 'C_USER' AS audience, 'INAPP' AS channel, 1 AS enabled, 'NORMAL' AS push_level UNION ALL
    SELECT 'SUB_ORDER_SHIPPED', 'C_USER', 'PUSH', 1, 'NORMAL' UNION ALL
    SELECT 'SUB_ORDER_SHIPPED', 'C_USER', 'WXSUB', 0, 'NORMAL'
) t
WHERE NOT EXISTS (
    SELECT 1 FROM notify_scene_channel m
    WHERE m.scene_code = t.scene_code AND m.audience = t.audience AND m.channel = t.channel
);
