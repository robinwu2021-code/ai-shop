-- Paid order fulfillment can change before hand-off; tell the buyer where to expect the goods.
INSERT INTO notify_scene_channel (scene_code, audience, channel, enabled, push_level, created_at, updated_at)
SELECT t.scene_code, t.audience, t.channel, t.enabled, t.push_level, NOW(), NOW()
FROM (
    SELECT 'SUB_ORDER_FULFILLMENT_CHANGED' AS scene_code, 'C_USER' AS audience,
           'INAPP' AS channel, 1 AS enabled, 'NORMAL' AS push_level UNION ALL
    SELECT 'SUB_ORDER_FULFILLMENT_CHANGED', 'C_USER', 'PUSH', 1, 'NORMAL'
) t
WHERE NOT EXISTS (
    SELECT 1 FROM notify_scene_channel m
    WHERE m.scene_code = t.scene_code AND m.audience = t.audience AND m.channel = t.channel
);
