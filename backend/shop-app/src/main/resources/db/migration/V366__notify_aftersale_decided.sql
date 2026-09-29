-- 售后结果的场景×通道种子（TDD-通知与消息推送 §11）。
--
-- 两条都是**买家正在等的结果**，所以 INAPP + PUSH 都开：
--   · 被驳回：不说他不知道下一步能做什么（要么放弃，要么来问客服）；
--   · 待寄回：**这条有时限** —— 不寄会被 AfterSaleTimeoutJob 自动关单，
--     而买家会以为「同意了就等着收钱」。
--
-- 不开 WXSUB：本小程序没有对应的报备模板（公共模板库里退款/售后那一类
-- 不是每个类目都有，见 §8.5），端上也没有在提交售后时收集过这两条的授权。
-- 等模板与采集点都就位再开，那是 §8.4 采集点表里「提交售后」那一行的事。

INSERT INTO notify_scene_channel (scene_code, audience, channel, enabled, push_level, created_at, updated_at)
SELECT t.scene_code, t.audience, t.channel, t.enabled, t.push_level, NOW(), NOW()
FROM (
    SELECT 'AFTER_SALE_REJECTED' AS scene_code, 'C_USER' AS audience, 'INAPP' AS channel, 1 AS enabled, 'NORMAL' AS push_level UNION ALL
    SELECT 'AFTER_SALE_REJECTED', 'C_USER', 'PUSH', 1, 'NORMAL' UNION ALL
    SELECT 'AFTER_SALE_REJECTED', 'C_USER', 'WXSUB', 0, 'NORMAL' UNION ALL
    SELECT 'AFTER_SALE_RETURN_WAIT', 'C_USER', 'INAPP', 1, 'NORMAL' UNION ALL
    SELECT 'AFTER_SALE_RETURN_WAIT', 'C_USER', 'PUSH', 1, 'NORMAL' UNION ALL
    SELECT 'AFTER_SALE_RETURN_WAIT', 'C_USER', 'WXSUB', 0, 'NORMAL'
) t
WHERE NOT EXISTS (
    SELECT 1 FROM notify_scene_channel m
    WHERE m.scene_code = t.scene_code AND m.audience = t.audience AND m.channel = t.channel
);
