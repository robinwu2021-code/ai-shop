-- 微信订阅消息优先（TDD-微信订阅消息优先 AC2–AC5 / AC9）：打开七个场景的 WXSUB。
--
-- 用户原话（2026-10-09）：除了排除重复推送，能进行微信推送的尽量用微信推送；商家也引导在小程序里授权。
--
-- 不开这些行的后果是**静默**：路由「查不到 = 关」，消费者里那几句 wxSender 永不执行（同 V364 / V389 的说明）。
-- 模板号没配时发送端静默跳过，开着不会出错 —— 关着的话，哪天配上了模板也发不出去。
--
-- 买家（C_USER）：
--   · AFTER_SALE_REJECTED     → 售后结果（带驳回理由）
--   · AFTER_SALE_RETURN_WAIT  → 退货寄回提醒（说清有时限）
--   · GROUP_FORMED / FAILED   → 拼团结果（失败说退款）
--   · SUB_ORDER_SHIPPED       → 只对商家配送发「开始配送」；快递发货不发（判断在 NotificationConsumer，AC6）
-- 商家（B_STAFF），店主的商家账号与 C 端同一个 user_no，小程序 openid 现成：
--   · SUB_ORDER_PAID          → 新订单（App 响铃与微信都发）
--   · AFTER_SALE_APPLIED      → 售后待处理（微信发出去了就不走 App）
--   · REVIEW_CREATED          → 新评价（同上）
--
-- 不动的：AFTER_SALE_REFUNDED 的 WXSUB 保持关（V373）—— 微信支付的退款到账微信支付自己推。

UPDATE notify_scene_channel
SET enabled = 1, updated_at = NOW()
WHERE channel = 'WXSUB' AND audience = 'C_USER'
  AND scene_code IN ('AFTER_SALE_REJECTED', 'AFTER_SALE_RETURN_WAIT', 'GROUP_FORMED', 'GROUP_FAILED',
                     'SUB_ORDER_SHIPPED');

UPDATE notify_scene_channel
SET enabled = 1, updated_at = NOW()
WHERE channel = 'WXSUB' AND audience = 'B_STAFF'
  AND scene_code IN ('SUB_ORDER_PAID', 'AFTER_SALE_APPLIED', 'REVIEW_CREATED');

INSERT INTO notify_scene_channel (scene_code, audience, channel, enabled, push_level, created_at, updated_at)
SELECT t.scene_code, t.audience, t.channel, t.enabled, t.push_level, NOW(), NOW()
FROM (
    SELECT 'AFTER_SALE_REJECTED' AS scene_code, 'C_USER' AS audience, 'WXSUB' AS channel, 1 AS enabled, 'NORMAL' AS push_level UNION ALL
    SELECT 'AFTER_SALE_RETURN_WAIT', 'C_USER', 'WXSUB', 1, 'NORMAL' UNION ALL
    SELECT 'GROUP_FORMED', 'C_USER', 'WXSUB', 1, 'NORMAL' UNION ALL
    SELECT 'GROUP_FAILED', 'C_USER', 'WXSUB', 1, 'NORMAL' UNION ALL
    SELECT 'SUB_ORDER_SHIPPED', 'C_USER', 'WXSUB', 1, 'NORMAL' UNION ALL
    SELECT 'SUB_ORDER_PAID', 'B_STAFF', 'WXSUB', 1, 'NORMAL' UNION ALL
    SELECT 'AFTER_SALE_APPLIED', 'B_STAFF', 'WXSUB', 1, 'NORMAL' UNION ALL
    SELECT 'REVIEW_CREATED', 'B_STAFF', 'WXSUB', 1, 'NORMAL'
) t
WHERE NOT EXISTS (
    SELECT 1 FROM notify_scene_channel m
    WHERE m.scene_code = t.scene_code AND m.audience = t.audience AND m.channel = t.channel
);
