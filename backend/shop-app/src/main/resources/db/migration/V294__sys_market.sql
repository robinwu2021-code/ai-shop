-- 市场主数据（S11 · TDD-支付域-数据库设计（目标态）§2.1）
--
-- **这不是从零新建，是把一段 JSON 升格为表。**
-- 市场今天已经存在：平台设置里有 `platform.markets` 一段 JSON
-- （code / name / currency / timezone / rate / enabled），运营端能改，
-- ops-web 也有页面。而 `market` 这个列早就在五张表上用着
-- （商品 SKU、门店价、积分账户、积分流水、积分池）——
-- 市场概念贯穿了两个域，只是没有主数据。
--
-- **那为什么还要建表**：不是「没有地方存」，而是 JSON 无法被引用与约束。
-- 今天没有任何东西保证某张表里的 `market` 值真的在那段 JSON 里 ——
-- 写错一个市场码，积分会记进一个不存在的市场，而不报错。
--
-- **迁移不搬数据**：2026-09-02 查过线上，sys_setting 里根本没有
-- platform.markets 这一行 —— 运营从没改过，一直在用代码默认值（只有 CN）。
-- 所以这里直接种，而不是从 JSON 读出来搬。
CREATE TABLE IF NOT EXISTS sys_market
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    market VARCHAR(8) NOT NULL COMMENT '市场码：CN / TW / HK / SG / AE / SA。**引用列一律叫 market**，与既有五张表同名，不新造 market_code',
    name VARCHAR(64) NOT NULL COMMENT '显示名。多语言走词条，不在这里存三份',
    currency VARCHAR(8) NOT NULL COMMENT '记账币种。**一个市场一种**，改它等于换账本',
    currency_scale TINYINT(4) NOT NULL DEFAULT 2 COMMENT '小数位。日元 0、科威特第纳尔 3 —— 写死 2 会让日元的金额差 100 倍，而它不会报错',
    time_zone VARCHAR(48) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '账期与对账按它切天',
    display_rate DECIMAL(18,6) NOT NULL DEFAULT 1 COMMENT '相对 CNY 的展示汇率。**只用于折算显示，绝不参与结算** —— 参与的话汇率一动历史账就变',
    enabled TINYINT(4) NOT NULL DEFAULT 0 COMMENT '默认关。开一个市场是运营动作，不是上线动作',
    sort_no INT(11) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_market (tenant_no, market, deleted)
) COMMENT='市场主数据。归 pay —— 币种与账期口径是资金域的知识';

-- 种子。
--
-- **只有 CN 是开的**，其余预置但关着 —— 开一个市场要先配通道、
-- 配费率、确认进件材料，那是一串运营动作。默认全开的话，
-- 上线当天商家能选到一个通道都没配的市场，而下单时才失败。
--
-- 时间戳写字面量、id 显式给：V288 在这两处各撞过一次
-- （UNIX_TIMESTAMP() 在 H2 跑不了；已有种子带 id 插入不推进自增序列）。
INSERT IGNORE INTO sys_market
    (id, market, name, currency, currency_scale, time_zone, display_rate, enabled, sort_no,
     tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (1, 'CN', '中国大陆', 'CNY', 2, 'Asia/Shanghai',  1.000000, 1, 10, 'MAIN',
     '2026-09-02 00:00:00', 'SYSTEM', '2026-09-02 00:00:00', 'SYSTEM', 0, 0),
    (2, 'TW', '中国台湾', 'TWD', 2, 'Asia/Taipei',    4.400000, 0, 20, 'MAIN',
     '2026-09-02 00:00:00', 'SYSTEM', '2026-09-02 00:00:00', 'SYSTEM', 0, 0),
    (3, 'HK', '中国香港', 'HKD', 2, 'Asia/Hong_Kong', 1.100000, 0, 30, 'MAIN',
     '2026-09-02 00:00:00', 'SYSTEM', '2026-09-02 00:00:00', 'SYSTEM', 0, 0),
    (4, 'SG', '新加坡',   'SGD', 2, 'Asia/Singapore', 0.190000, 0, 40, 'MAIN',
     '2026-09-02 00:00:00', 'SYSTEM', '2026-09-02 00:00:00', 'SYSTEM', 0, 0),
    (5, 'AE', '阿联酋',   'AED', 2, 'Asia/Dubai',     0.520000, 0, 50, 'MAIN',
     '2026-09-02 00:00:00', 'SYSTEM', '2026-09-02 00:00:00', 'SYSTEM', 0, 0),
    (6, 'SA', '沙特',     'SAR', 2, 'Asia/Riyadh',    0.530000, 0, 60, 'MAIN',
     '2026-09-02 00:00:00', 'SYSTEM', '2026-09-02 00:00:00', 'SYSTEM', 0, 0);
