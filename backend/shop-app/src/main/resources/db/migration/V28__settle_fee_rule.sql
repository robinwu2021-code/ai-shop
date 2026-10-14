-- 费率规则表（落地清单 P1-4）：把费率从配置文件搬进可运营、可回查的表。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 修的是什么
-- ─────────────────────────────────────────────────────────────────────────────
-- 费率此前写在 application.yml 里：
--   shop.settle.merchant-owned-rate=0     自带客流
--   shop.settle.platform-rate=500         平台客流 5%
-- 于是**改一次费率要改配置文件 + 重启**，而费率是最会被反复调的东西之一。
--
-- 快照那一半原本就做对了：stl_bill.commission_rate 逐单落快照，
-- 历史账不会跟着配置变。这次只换取数来源，不动快照。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么是二维，而不是「自营毛利率 / 第三方佣金率」两个字段
-- ─────────────────────────────────────────────────────────────────────────────
-- 现有费率的划分维度是**流量来源**（自带客流 / 平台客流），
-- 而经营模式（自营 / 第三方）是另一个维度。两者正交：
--
--                自带客流   平台客流
--   自营            ?          ?
--   第三方          0%         5%
--
-- 只按经营模式建一维表，等哪天想给自营也区分客流就要改表结构 ——
-- 而费率表恰恰是最不该改结构的表：历史行要一直可读。
-- 四行里自营那两格先填成与第三方一致，不影响任何现有行为。
--
-- 两种模式下这个数的**记账口径不同**（自营是进销差价即毛利，第三方是服务收入即佣金），
-- 但算法完全一样（gross × rate ÷ 10000），且口径由 stl_bill.business_mode
-- 快照决定 —— 所以不需要为它多开一列，只需要在这里说清楚。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么带生效时间而不是原地改
-- ─────────────────────────────────────────────────────────────────────────────
-- 原地改 = 只能回答「现在是多少」。而真正会被问到的是
-- 「上个月那批单当时按什么费率算的、谁什么时候改的」。
-- 调费率一律**插新行**，旧行永久保留；取数时按 effective_from <= 当前时刻
-- 取最新一条。这也让「预约生效」自然成立：填一个未来时间即可。

CREATE TABLE IF NOT EXISTS stl_fee_rule
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    rule_no VARCHAR(64) NOT NULL COMMENT '规则单号',
    business_mode VARCHAR(24) NOT NULL COMMENT 'SELF_OPERATED 自营（毛利率）/ THIRD_PARTY 第三方（佣金率）',
    traffic_source VARCHAR(24) NOT NULL COMMENT 'MERCHANT_OWNED 自带客流 / PLATFORM 平台客流',
    rate_bp INT(11) NOT NULL DEFAULT 0 COMMENT '万分比。500 = 5%',
    -- 用毫秒时间戳而不是 DATETIME：与 stl_bill 及全站的时间字段保持同一口径，
    -- 免得比较时要在两种类型之间来回转
    effective_from BIGINT(20) NOT NULL COMMENT '生效时刻（毫秒）；未来时间 = 预约生效',
    enabled TINYINT(4) NOT NULL DEFAULT 1,
    remark VARCHAR(255) DEFAULT NULL COMMENT '为什么调这一次 —— 回查时这句话比数字更有用',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fee_rule_no (rule_no, tenant_no),
    -- 同一格同一时刻只能有一条，否则「取最新一条」会变成随机取
    UNIQUE KEY uk_fee_rule_slot (business_mode, traffic_source, effective_from, tenant_no)
) COMMENT='费率规则：经营模式 × 流量来源，按生效时间分版本';

-- 初始四行 = 现有 application.yml 的两个默认值，**行为与上线前完全一致**。
-- effective_from = 0 表示「自古以来」，让存量订单回查时也能命中一条规则。
INSERT INTO stl_fee_rule
    (rule_no, business_mode, traffic_source, rate_bp, effective_from, enabled, remark,
     tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    ('FR-INIT-TP-OWNED', 'THIRD_PARTY', 'MERCHANT_OWNED', 0, 0, 1,
     '自带客流零佣金：他带来的客户在别家消费才是平台收益（R16）',
     'MAIN', NOW(), 'SYSTEM', NOW(), NULL, 0, 0),
    ('FR-INIT-TP-PLAT', 'THIRD_PARTY', 'PLATFORM', 500, 0, 1,
     '平台客流 5%，沿用上线前 shop.settle.platform-rate 的默认值',
     'MAIN', NOW(), 'SYSTEM', NOW(), NULL, 0, 0),
    ('FR-INIT-SO-OWNED', 'SELF_OPERATED', 'MERCHANT_OWNED', 0, 0, 1,
     '自营·自带客流：先与第三方取齐，等自营有量后再单独定',
     'MAIN', NOW(), 'SYSTEM', NOW(), NULL, 0, 0),
    ('FR-INIT-SO-PLAT', 'SELF_OPERATED', 'PLATFORM', 500, 0, 1,
     '自营·平台客流：先与第三方取齐，等自营有量后再单独定',
     'MAIN', NOW(), 'SYSTEM', NOW(), NULL, 0, 0);
