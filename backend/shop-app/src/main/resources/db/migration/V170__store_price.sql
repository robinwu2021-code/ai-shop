-- 门店定价（商品域-优化总方案 批 C）。
--
-- 「连锁」这个取舍的最后一块：同一批货挂在主体上，**价可以各店不同**
-- （ADR-011 双键模型 —— 商品挂 entity_no，履约挂 store_no）。
-- 没有它，多门店商家要么被迫全网一个价，要么只能给每家店各建一套商品，
-- 而后者会让「同一件货的销量」在报表里裂成几份。
--
-- ⚠️ **回退方向与库存相反，这是唯一不同构的地方。**
--
--   | 无门店行时 | 理由                                                   |
--   |------------|--------------------------------------------------------|
--   | 门店库存   | 视为 0（fail-closed）：回退主体总量 = 没配过的店无限供应 |
--   | 门店价格   | 回退主体价（fail-back）：视为 0 就是白送                 |
--
-- 照抄库存那套写法的症状：一家没配过价的店把所有货以 ¥0.00 卖出去，
-- 页面上看着像 bug，钱已经出去了。

CREATE TABLE IF NOT EXISTS prd_store_price
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    store_no      VARCHAR(64)  NOT NULL COMMENT '门店业务键（mch_store.store_no）',
    sku_no        VARCHAR(64)  NOT NULL,
    entity_no     VARCHAR(64)  NOT NULL COMMENT '冗余主体号：按商家查全部门店价时免 join，也是数据域锚点',
    -- 市场进主键：一个 SKU 在三个市场是三行价，与 prd_sku 的写法一致。
    -- 不带它的话，切市场会把另一个市场的门店价一起改掉，而两边都不报错
    market        VARCHAR(8)   NOT NULL DEFAULT 'CN' COMMENT '市场码，与 prd_sku.market 同一套',
    price         BIGINT       NOT NULL COMMENT '这家店的售价，最小货币单位',
    origin_price  BIGINT       DEFAULT NULL COMMENT '这家店的划线价。空 = 用主体的划线价',
    tenant_no     VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_by    VARCHAR(64)  DEFAULT NULL,
    updated_by    VARCHAR(64)  DEFAULT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 一店一 SKU 一市场一行。并发靠唯一键兜底，与 prd_store_stock 同一手法
    UNIQUE KEY uk_store_sku_market (store_no, sku_no, market),
    KEY idx_sku (sku_no),
    KEY idx_entity (entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='门店级售价：有行按店算，无行回退主体价（与库存相反，视为 0 就是白送）';
