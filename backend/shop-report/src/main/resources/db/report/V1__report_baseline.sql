-- 报表库基线（TDD-B端报表库与日结 §2.2）。
--
-- ─────────────────────────────────────────────────────────────────────────
-- 这个库里的数据全部是**派生的**
-- ─────────────────────────────────────────────────────────────────────────
-- 每一行都能由平台库重算出来。所以：
--   · 不参与任何业务事务 —— 日结失败就重跑，不会让下单回滚；
--   · 丢了不是事故，是一次重跑（把 shop.report.rollback-days 调大跑一遍）；
--   · 因此也**不需要**软删、租户列、数据域拦截器这些东西。
--
-- ⚠️ 代价是**隔离靠查询层**：本库没有行级数据域拦截器，
-- 每个查询方法必须自己显式带 entity_no。漏一个条件就是越权，而且不会报错。
--
-- ⚠️ 迁移号从 V1 重来，历史表是 rpt_flyway_history —— 与平台库的
-- flyway_schema_history 不混用（见 ReportDataSourceConfig）。
--
-- 排序规则：**不写 COLLATE**。生产主库已是 MySQL 9.7，uca1400 是 MariaDB 的，
-- 照抄老迁移会建不起来。

-- ─────────────────────────────────────────────────────────────────────────
-- 门店日汇总：R1 近几日 / R2 按月 / 自带客流占比 的底座
-- ─────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS rpt_daily_store
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    -- 统计日。**成交归下单日，退款归退款发生日** —— 不回冲原单那天。
    -- 回冲的后果是：昨天截图发群里的数字，今天再看会变。
    stat_date DATE NOT NULL COMMENT '统计日（自然日）',
    entity_no VARCHAR(64) NOT NULL COMMENT '商户主体。**查询必须带它** —— 本库无数据域拦截器',
    -- 门店级永远落行；要看主体合计时在查询层 SUM，不额外落一行「合计行」——
    -- 合计行会在「按店筛」时被算进去，而那种错不报错
    store_no VARCHAR(64) NOT NULL COMMENT '门店。合计在查询层 SUM，不落合计行',

    orders INT(11) NOT NULL DEFAULT 0 COMMENT '成交单量（口径 = OrdSubOrder.TRANSACTED）',
    gmv_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '成交额（分）',
    refund_orders INT(11) NOT NULL DEFAULT 0 COMMENT '退款单量，按退款发生日归属',
    refund_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '退款额（分），按退款发生日归属',

    buyers INT(11) NOT NULL DEFAULT 0 COMMENT '当日下单人数（去重）',
    new_buyers INT(11) NOT NULL DEFAULT 0 COMMENT '其中首单人数',

    -- 客流来源只有 MERCHANT_OWNED / PLATFORM 两个值（ord_sub_order.traffic_source
    -- 的列注释：「下单时固化」）。两个值不值得单独建一张表 —— 落成两列，
    -- 平台那部分 = orders - owned_orders
    owned_orders INT(11) NOT NULL DEFAULT 0 COMMENT '自带客流的单量',
    owned_gmv_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '自带客流的成交额（分）',

    commission_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '佣金（分）',
    service_fee_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '履约服务费（分）',
    freight_income_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '运费收入（分）',
    freight_cost_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '运费成本（分）',
    -- 毛 − 佣金 − 服务费 ± 运费。**落表而不是查询时算** ——
    -- 算法改了之后历史行仍是当时的口径，那是对的：报表描述的是那一天
    net_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '应结（分）',

    currency VARCHAR(8) NOT NULL DEFAULT 'CNY' COMMENT '币种，跟着金额走',

    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    -- 日结是「先删后写」，靠这个唯一键保证重跑幂等
    UNIQUE KEY uk_rpt_daily_store (stat_date, entity_no, store_no),
    -- 报表查询的形状固定是「某商户 + 某时间段」，所以把 entity_no 放在最前
    KEY idx_rpt_daily_store_entity (entity_no, stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门店日汇总（派生，可重算）';

-- ─────────────────────────────────────────────────────────────────────────
-- 作业水位：AC8 的全部依据
-- ─────────────────────────────────────────────────────────────────────────
-- 没有这张表的话，「昨天的数据没生成」的表现是报表里少一行 ——
-- 而少一行和「那天真的没单」长得一模一样。
CREATE TABLE IF NOT EXISTS rpt_job_watermark
(
    job_key VARCHAR(64) NOT NULL COMMENT '统计口径，如 daily-store / daily-goods',
    last_stat_date DATE DEFAULT NULL COMMENT '已经算到哪一天（含）',
    last_run_at DATETIME DEFAULT NULL COMMENT '上次跑完的时间',
    rows_written INT(11) NOT NULL DEFAULT 0 COMMENT '上次写了多少行',
    duration_ms BIGINT(20) NOT NULL DEFAULT 0 COMMENT '上次耗时',
    PRIMARY KEY (job_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日结作业水位（读侧靠它识别缺口）';
