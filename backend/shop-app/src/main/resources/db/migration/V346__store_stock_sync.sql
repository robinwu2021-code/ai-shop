-- 门店库存第二期：进销存 → 商城写回（TDD-商品纳入进销存开关 §18）。
--
-- 三张表都挂门店，建在主库：写回在平台侧执行、改的是 prd_store_stock。
--   prd_sell_rule          线上可售规则。稀疏：没有行 = 全部可售；取值顺序 单品 › 品类 › 本店默认
--   prd_store_stock_sync   每店一行：期初对齐过没有、同步开没开。没对齐不许开
--   prd_stock_sync_log     同步明细。唯一键 (source_ref, store_no, sku_no) 即幂等键：同一张单据重投只写一次

CREATE TABLE IF NOT EXISTS prd_sell_rule
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    store_no VARCHAR(64) NOT NULL COMMENT '门店',
    scope_type VARCHAR(16) NOT NULL COMMENT 'STORE 本店默认 / CATEGORY 类目 / GOODS 商品',
    scope_ref VARCHAR(64) NOT NULL COMMENT 'STORE 时为门店号，其余为类目号 / 商品号',
    rule_type VARCHAR(16) NOT NULL COMMENT 'ALL 全部可售 / RESERVE 保留线下 N / RATIO 按比例 P% / CAP 封顶 M / MANUAL 手动',
    param INT(11) NOT NULL DEFAULT 0 COMMENT 'RESERVE/CAP/MANUAL 为件数，RATIO 为百分比',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_prd_sell_rule (store_no, scope_type, scope_ref)
) COMMENT='门店线上可售规则。行只改不删，唯一键不含 deleted';

CREATE TABLE IF NOT EXISTS prd_store_stock_sync
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    store_no VARCHAR(64) NOT NULL COMMENT '门店',
    entity_no VARCHAR(64) NOT NULL COMMENT '主体',
    enabled TINYINT(4) NOT NULL DEFAULT 0 COMMENT '1 进销存过账后写回商城',
    aligned_at DATETIME DEFAULT NULL COMMENT '期初对齐时间。为空不许打开同步',
    aligned_by VARCHAR(64) DEFAULT NULL,
    align_mode VARCHAR(16) DEFAULT NULL COMMENT 'MALL 以商城为准 / COUNT 已实地盘点',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_prd_store_stock_sync (store_no)
) COMMENT='门店库存同步开关与期初对齐';

CREATE TABLE IF NOT EXISTS prd_stock_sync_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    store_no VARCHAR(64) NOT NULL COMMENT '门店',
    sku_no VARCHAR(64) NOT NULL COMMENT 'SKU',
    source_ref VARCHAR(96) NOT NULL COMMENT '触发来源：单据号 / RULE:… / SWEEP:日期 / ALIGN:…',
    rule_type VARCHAR(16) NOT NULL COMMENT '当时生效的规则',
    available INT(11) NOT NULL COMMENT '可用 = 实存 − 占用 − 安全库存',
    before_qty INT(11) NOT NULL COMMENT '写回前线上可卖',
    after_qty INT(11) NOT NULL COMMENT '写回后线上可卖',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_prd_stock_sync_log (source_ref, store_no, sku_no),
    KEY idx_prd_stock_sync_log_sku (store_no, sku_no, created_at)
) COMMENT='进销存 → 商城写回明细';
