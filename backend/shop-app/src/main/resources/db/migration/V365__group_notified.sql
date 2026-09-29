-- 拼团结果通知的幂等标记 + 场景×通道种子（TDD-通知与消息推送 §10）。
--
-- 一、mkt_group_buy 加「结果已通知」时间戳
--
-- 成团发生在 GroupJoinPortImpl 的一条**原子 UPDATE** 里
-- （status = CASE WHEN joined_count + 1 >= min_count THEN 'FORMED' ELSE status END），
-- 每一个后付的人都会再走一遍那条 UPDATE，团照旧是 FORMED。
-- 挂在「状态是 FORMED」上的话，第 5、第 6 个人付款时会再通知一遍全团。
--
-- 挂在这一列上则天然幂等：带条件 UPDATE 只有一个线程能把它从 NULL 改成非 NULL，
-- 抢到的那个负责发事件。与 prd_goods.new_notified_at 同一个套路（V356）。
--
-- 可空：存量团一行都不用补 —— 它们的结果早就过去了，现在补发通知是打扰。

ALTER TABLE mkt_group_buy
    ADD COLUMN notified_at BIGINT DEFAULT NULL COMMENT '成团/失败通知已发送时间（毫秒）。为空=还没发过';

-- 二、场景 × 通道种子
--
-- 两种结果分开登记，运营可以分别开关：
--   · 成团是喜讯，INAPP + PUSH；
--   · **未成团关系到退款**，同样 INAPP + PUSH —— 钱退回去了而人不知道，
--     下次他不会再开团。
--
-- 都不开 WXSUB：这两条要发给**全团的人**，而订阅消息是一次授权一条，
-- 团里多数人没在这个场景下授权过，发出去大半会被 43101 拒。
-- 真要上，得先在开团/参团页收集授权（§8.4 的采集点表里列了这一条）。

INSERT INTO notify_scene_channel (scene_code, audience, channel, enabled, push_level, created_at, updated_at)
SELECT t.scene_code, t.audience, t.channel, t.enabled, t.push_level, NOW(), NOW()
FROM (
    SELECT 'GROUP_FORMED' AS scene_code, 'C_USER' AS audience, 'INAPP' AS channel, 1 AS enabled, 'NORMAL' AS push_level UNION ALL
    SELECT 'GROUP_FORMED', 'C_USER', 'PUSH', 1, 'NORMAL' UNION ALL
    SELECT 'GROUP_FORMED', 'C_USER', 'WXSUB', 0, 'NORMAL' UNION ALL
    SELECT 'GROUP_FAILED', 'C_USER', 'INAPP', 1, 'NORMAL' UNION ALL
    SELECT 'GROUP_FAILED', 'C_USER', 'PUSH', 1, 'NORMAL' UNION ALL
    SELECT 'GROUP_FAILED', 'C_USER', 'WXSUB', 0, 'NORMAL'
) t
WHERE NOT EXISTS (
    SELECT 1 FROM notify_scene_channel m
    WHERE m.scene_code = t.scene_code AND m.audience = t.audience AND m.channel = t.channel
);
