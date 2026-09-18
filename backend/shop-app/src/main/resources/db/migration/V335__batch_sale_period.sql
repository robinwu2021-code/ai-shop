-- 社区集单（TDD-营销-活动统一模型与集单 §2.2 · ADR-024）。
--
-- 集单是活动的一种触发（CUTOFF：到截单时刻成交），「一期」是它的实例 ——
-- 与拼团 GROUP → mkt_group_buy 同一个形状。线上 pmt_activity 1 行、ful_batch 0 行，
-- 没有存量要迁：加的全是可空列，非集单的行为字节级不变。

-- ── 1. 活动上的集单 / 拼团参数（只对对应触发有意义，其余玩法全为 NULL） ─────────

ALTER TABLE pmt_activity
    ADD COLUMN cutoff_time VARCHAR(5) DEFAULT NULL COMMENT 'CUTOFF：每期截单时刻 HH:mm，市场时区',
    ADD COLUMN pickup_offset INT(11) DEFAULT NULL COMMENT 'CUTOFF：提货日 = 截单日 + N 天',
    ADD COLUMN pickup_from VARCHAR(5) DEFAULT NULL COMMENT 'CUTOFF：提货日几点起可取 HH:mm',
    ADD COLUMN min_qty INT(11) DEFAULT NULL COMMENT 'CUTOFF：起订量（份）。NULL = 不设，截单即成',
    ADD COLUMN period_quota INT(11) DEFAULT NULL COMMENT 'CUTOFF：每期份数上限。NULL = 不限',
    ADD COLUMN decide_hours INT(11) DEFAULT NULL COMMENT 'CUTOFF：未达起订量时商家的处理时限（小时）。NULL = 取 shop.period.decide-hours',
    ADD COLUMN group_hours INT(11) DEFAULT NULL COMMENT 'GROUP：开团后多少小时内成团。NULL = 24';

-- ── 2. 一期 ──────────────────────────────────────────────────────────────────
--
-- **份数与金额不存**，从订单现算：与 FulfillmentStatsPort 同一条原则 ——
-- 存一份计数，迟早「总览 86、点进去 85」，而那种不一致不报错也无从复现。

CREATE TABLE IF NOT EXISTS pmt_period
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    period_no VARCHAR(64) NOT NULL,
    activity_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL COMMENT '商家主体。数据域锚点',
    period_date VARCHAR(10) NOT NULL COMMENT 'YYYY-MM-DD，截单日（市场时区）',
    cutoff_at BIGINT(20) NOT NULL COMMENT '截单时刻；提前截单时改写',
    pickup_date VARCHAR(10) NOT NULL COMMENT 'YYYY-MM-DD，写进订单的 arrive_date',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 收单中 / SHORT 未达起订待处理 / CONFIRMED 已成 / CANCELLED 已取消',
    decide_deadline BIGINT(20) DEFAULT NULL COMMENT 'SHORT 时：过了这个点商家未处理即自动取消。进入 SHORT 那一刻按活动的 decide_hours 写死',
    decided_by VARCHAR(64) DEFAULT NULL COMMENT 'SHORT 的处理人；SYSTEM = 超时自动取消',
    decided_at BIGINT(20) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_period_no (period_no),
    UNIQUE KEY uk_pmt_period_day (tenant_no, activity_no, period_date),
    KEY idx_pmt_period_due (status, cutoff_at),
    KEY idx_pmt_period_entity (entity_no, status)
) COMMENT='集单的一期。份数与金额不存，从订单现算';

-- ── 3. 订单挂到期上 ─────────────────────────────────────────────────────────

ALTER TABLE ord_sub_order
    ADD COLUMN period_no VARCHAR(64) DEFAULT NULL COMMENT '集单下单时的期号',
    ADD COLUMN arrive_date VARCHAR(10) DEFAULT NULL COMMENT '提货日 YYYY-MM-DD。NULL = 沿用下单日（非集单单，口径不变）';

CREATE INDEX idx_sub_order_period ON ord_sub_order (period_no);
