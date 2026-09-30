-- 库存上传二期（TDD-元器件-库存上传二期 §2.6）。
-- V1、V2 已在生产执行，一个字节都不许改（ElecAppliedMigrationsFrozenTest）；表结构的变化都在这里。

-- ─────────────────────────────────────────────────────────────────────────────
-- 表头别名：哪种写法的表头是哪个字段。原先写死在 Columns.NAMES 里，搬进库是为了
-- 运营能加、供应商确认过的写法能记成他自己的别名（下次同样写法不用再问大模型）。
-- supplier_no = '' 表示全局。不用 NULL：MySQL 的唯一键不管 NULL，同一写法会插进好几条全局别名。
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS elc_header_alias
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    supplier_no VARCHAR(32) NOT NULL DEFAULT '' COMMENT '空串 = 全局；否则只对这家生效',
    alias_norm  VARCHAR(64) NOT NULL COMMENT '规范化后的表头写法（HeaderNames.norm：大写、只留字母数字与 /，P/N 记作 PN）',
    alias_raw   VARCHAR(64) NOT NULL COMMENT '第一次见到时的原文，给运营看',
    field       VARCHAR(16) NOT NULL COMMENT 'MPN / MFR / QTY / DC / PACKAGE / PRICE / MOQ / SPQ / PACKING / CONDITION / CURRENCY / LEAD / REGION',
    source      VARCHAR(16) NOT NULL COMMENT 'SEED 种子 / OPS 运营加的 / LEARNED 供应商确认过的',
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(64) DEFAULT NULL,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by  VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_elc_header_alias (supplier_no, alias_norm)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存表表头别名';

-- 种子 = 原 Columns.NAMES 逐条（HeaderAliasSeedTest 比对）。唯一的例外：PACKAGING 原先同时列在
-- 封装与包装方式两个字段下（同一列会被两个字段同时认走），这里只归包装方式 —— 行业惯例里
-- Packaging 指卷带 / 托盘 / 管装，封装写作 Package / Case。
INSERT IGNORE INTO elc_header_alias (supplier_no, alias_norm, alias_raw, field, source) VALUES
('', '型号', '型号', 'MPN', 'SEED'),
('', '料号', '料号', 'MPN', 'SEED'),
('', '物料型号', '物料型号', 'MPN', 'SEED'),
('', '产品型号', '产品型号', 'MPN', 'SEED'),
('', '规格型号', '规格型号', 'MPN', 'SEED'),
('', '型号规格', '型号规格', 'MPN', 'SEED'),
('', '物料编码', '物料编码', 'MPN', 'SEED'),
('', '器件型号', '器件型号', 'MPN', 'SEED'),
('', 'PARTNO', 'PARTNO', 'MPN', 'SEED'),
('', 'PARTNUMBER', 'PARTNUMBER', 'MPN', 'SEED'),
('', 'PN', 'PN', 'MPN', 'SEED'),
('', 'MPN', 'MPN', 'MPN', 'SEED'),
('', 'MODEL', 'MODEL', 'MPN', 'SEED'),
('', 'MFRPARTNO', 'MFRPARTNO', 'MPN', 'SEED'),
('', 'MFGPARTNO', 'MFGPARTNO', 'MPN', 'SEED'),
('', '品牌', '品牌', 'MFR', 'SEED'),
('', '厂牌', '厂牌', 'MFR', 'SEED'),
('', '厂家', '厂家', 'MFR', 'SEED'),
('', '厂商', '厂商', 'MFR', 'SEED'),
('', '制造商', '制造商', 'MFR', 'SEED'),
('', '生产商', '生产商', 'MFR', 'SEED'),
('', 'BRAND', 'BRAND', 'MFR', 'SEED'),
('', 'MFR', 'MFR', 'MFR', 'SEED'),
('', 'MFG', 'MFG', 'MFR', 'SEED'),
('', 'MANUFACTURER', 'MANUFACTURER', 'MFR', 'SEED'),
('', 'MAKER', 'MAKER', 'MFR', 'SEED'),
('', '数量', '数量', 'QTY', 'SEED'),
('', '库存', '库存', 'QTY', 'SEED'),
('', '库存数量', '库存数量', 'QTY', 'SEED'),
('', '现货数量', '现货数量', 'QTY', 'SEED'),
('', '现货', '现货', 'QTY', 'SEED'),
('', '可售数量', '可售数量', 'QTY', 'SEED'),
('', '数目', '数目', 'QTY', 'SEED'),
('', 'QTY', 'QTY', 'QTY', 'SEED'),
('', 'QUANTITY', 'QUANTITY', 'QTY', 'SEED'),
('', 'STOCK', 'STOCK', 'QTY', 'SEED'),
('', 'STOCKQTY', 'STOCKQTY', 'QTY', 'SEED'),
('', 'AVAILABLE', 'AVAILABLE', 'QTY', 'SEED'),
('', '批号', '批号', 'DC', 'SEED'),
('', '批次', '批次', 'DC', 'SEED'),
('', '年份', '年份', 'DC', 'SEED'),
('', '生产日期', '生产日期', 'DC', 'SEED'),
('', 'DC', 'DC', 'DC', 'SEED'),
('', 'DATECODE', 'DATECODE', 'DC', 'SEED'),
('', 'D/C', 'D/C', 'DC', 'SEED'),
('', 'LOT', 'LOT', 'DC', 'SEED'),
('', '封装', '封装', 'PACKAGE', 'SEED'),
('', '封装规格', '封装规格', 'PACKAGE', 'SEED'),
('', 'PACKAGE', 'PACKAGE', 'PACKAGE', 'SEED'),
('', 'PKG', 'PKG', 'PACKAGE', 'SEED'),
('', 'CASE', 'CASE', 'PACKAGE', 'SEED'),
('', '单价', '单价', 'PRICE', 'SEED'),
('', '价格', '价格', 'PRICE', 'SEED'),
('', '报价', '报价', 'PRICE', 'SEED'),
('', '含税单价', '含税单价', 'PRICE', 'SEED'),
('', '未税单价', '未税单价', 'PRICE', 'SEED'),
('', '不含税单价', '不含税单价', 'PRICE', 'SEED'),
('', '含税价', '含税价', 'PRICE', 'SEED'),
('', '未税价', '未税价', 'PRICE', 'SEED'),
('', 'PRICE', 'PRICE', 'PRICE', 'SEED'),
('', 'UNITPRICE', 'UNITPRICE', 'PRICE', 'SEED'),
('', '起订量', '起订量', 'MOQ', 'SEED'),
('', '最小起订量', '最小起订量', 'MOQ', 'SEED'),
('', '最小订购量', '最小订购量', 'MOQ', 'SEED'),
('', 'MOQ', 'MOQ', 'MOQ', 'SEED'),
('', 'MINQTY', 'MINQTY', 'MOQ', 'SEED'),
('', '最小包装量', '最小包装量', 'SPQ', 'SEED'),
('', '包装量', '包装量', 'SPQ', 'SEED'),
('', '标准包装', '标准包装', 'SPQ', 'SEED'),
('', '整包数量', '整包数量', 'SPQ', 'SEED'),
('', 'SPQ', 'SPQ', 'SPQ', 'SEED'),
('', 'MPQ', 'MPQ', 'SPQ', 'SEED'),
('', 'PACKQTY', 'PACKQTY', 'SPQ', 'SEED'),
('', '包装', '包装', 'PACKING', 'SEED'),
('', '包装方式', '包装方式', 'PACKING', 'SEED'),
('', '包装形式', '包装形式', 'PACKING', 'SEED'),
('', 'PACKING', 'PACKING', 'PACKING', 'SEED'),
('', 'PACKAGING', 'PACKAGING', 'PACKING', 'SEED'),
('', 'PACKTYPE', 'PACKTYPE', 'PACKING', 'SEED'),
('', '品质', '品质', 'CONDITION', 'SEED'),
('', '货况', '货况', 'CONDITION', 'SEED'),
('', '品相', '品相', 'CONDITION', 'SEED'),
('', '新旧', '新旧', 'CONDITION', 'SEED'),
('', '质量', '质量', 'CONDITION', 'SEED'),
('', 'CONDITION', 'CONDITION', 'CONDITION', 'SEED'),
('', 'QUALITY', 'QUALITY', 'CONDITION', 'SEED'),
('', 'GRADE', 'GRADE', 'CONDITION', 'SEED'),
('', '币种', '币种', 'CURRENCY', 'SEED'),
('', '货币', '货币', 'CURRENCY', 'SEED'),
('', 'CURRENCY', 'CURRENCY', 'CURRENCY', 'SEED'),
('', 'CCY', 'CCY', 'CURRENCY', 'SEED'),
('', '交期', '交期', 'LEAD', 'SEED'),
('', '货期', '货期', 'LEAD', 'SEED'),
('', '交货期', '交货期', 'LEAD', 'SEED'),
('', '供货周期', '供货周期', 'LEAD', 'SEED'),
('', 'LEADTIME', 'LEADTIME', 'LEAD', 'SEED'),
('', 'LEAD', 'LEAD', 'LEAD', 'SEED'),
('', 'DELIVERY', 'DELIVERY', 'LEAD', 'SEED'),
('', '货源地', '货源地', 'REGION', 'SEED'),
('', '所在地', '所在地', 'REGION', 'SEED'),
('', '发货地', '发货地', 'REGION', 'SEED'),
('', '仓库', '仓库', 'REGION', 'SEED'),
('', '货位', '货位', 'REGION', 'SEED'),
('', 'LOCATION', 'LOCATION', 'REGION', 'SEED'),
('', 'REGION', 'REGION', 'REGION', 'SEED'),
('', 'WAREHOUSE', 'WAREHOUSE', 'REGION', 'SEED');

-- ─────────────────────────────────────────────────────────────────────────────
-- 批次：认列来源、原件在哪、解析失败的原因
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE elc_stock_batch ADD COLUMN row_warn INT NOT NULL DEFAULT 0 COMMENT '警告行数：照常上架，但列给供应商看';
ALTER TABLE elc_stock_batch ADD COLUMN header_row INT DEFAULT NULL COMMENT '表头在第几行（从 0 起）；从原件重建时按它解析';
ALTER TABLE elc_stock_batch ADD COLUMN column_source VARCHAR(512) DEFAULT NULL COMMENT '字段 → REMEMBERED / ALIAS / AI / MANUAL，JSON';
ALTER TABLE elc_stock_batch ADD COLUMN ai_used TINYINT NOT NULL DEFAULT 0 COMMENT '这次调过大模型没有';
ALTER TABLE elc_stock_batch ADD COLUMN file_path VARCHAR(255) DEFAULT NULL COMMENT '原件相对路径 yyyy-MM-dd/供应商号/原名_批次号.xlsx，不含区';
ALTER TABLE elc_stock_batch ADD COLUMN file_area VARCHAR(16) DEFAULT NULL COMMENT 'FAILED 未入库区 / APPLIED 已入库区';
ALTER TABLE elc_stock_batch ADD COLUMN file_size INT DEFAULT NULL COMMENT '原件字节数';
ALTER TABLE elc_stock_batch ADD COLUMN file_sha256 CHAR(64) DEFAULT NULL COMMENT '原件 SHA-256';
ALTER TABLE elc_stock_batch ADD COLUMN file_purged_at DATETIME DEFAULT NULL COMMENT '清理任务删掉原件的时刻；非空 = 原件已不在';
ALTER TABLE elc_stock_batch ADD COLUMN fail_code VARCHAR(48) DEFAULT NULL COMMENT '解析失败时的错误码键（status = FAILED）';
ALTER TABLE elc_stock_batch ADD INDEX idx_elc_batch_file (file_area, status);

-- 原样行表改为只存「确认时有问题的行」（导出问题行用；原件被清掉之后也能导出）
ALTER TABLE elc_stock_batch_row ADD COLUMN issues VARCHAR(1024) DEFAULT NULL COMMENT '这一行的问题 JSON：[{c,l,col,v}]';
ALTER TABLE elc_stock_batch_row ADD COLUMN issue_level VARCHAR(8) DEFAULT NULL COMMENT '这一行最重的级别：ERROR / WARN';
