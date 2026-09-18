-- 拼团接通下单（TDD-营销域-详细设计 §2.4 · 开发计划 P1b G1）。
--
-- 参团从「插一行成员」改成「带团号下单、付款成功才落成员」。
-- 线上 mkt_group_buy 2 行、挂团的子单 0 行：加的全是可空列，存量行为空即可。

-- ── 1. 团挂活动：详情页要说得出「属于哪个活动」，退款与统计也按它归 ──────────────

ALTER TABLE mkt_group_buy
    ADD COLUMN activity_no VARCHAR(64) DEFAULT NULL COMMENT '开团时依据的拼团活动。存量 2 行为空';

-- ── 2. 成员挂子单：付款回调重放时按子单号去重，成员只加一次 ──────────────────────

ALTER TABLE mkt_group_member
    ADD COLUMN sub_order_no VARCHAR(64) DEFAULT NULL COMMENT '参团付款的子单。付款成功才落成员行';

CREATE UNIQUE INDEX uk_group_member_sub ON mkt_group_member (sub_order_no);

-- ── 3. 团到期 / 散团退款按团号找已付款子单 ──────────────────────────────────────

CREATE INDEX idx_sub_order_group ON ord_sub_order (group_no);
