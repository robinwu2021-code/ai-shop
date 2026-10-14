-- 行政区划主数据（ADR-013 阶段一）。
--
-- 经营范围要能表达「这三个小区 + 整个西湖区」，就得先有一棵可以被引用的地理树。
-- 在此之前只有 cmt_community.city_code 一个**自由文本** VARCHAR ——
-- 它拼不出层级，也没法回答「这个社区在哪个区」。
--
-- ⚠️ **这里只建表，数据由 V31（Java 迁移）灌**。
--    四级共 44703 行，写成 INSERT 的话 gen-test-schema.py 会把它们原样抄进
--    schema-test.sql（那个生成器保留非 SELECT 的 INSERT 作为种子）——
--    一份刚修好的生成物会涨到几 MB，且每个 Spring 测试上下文都要灌一遍。
--    参考数据不是 schema，不该让每次 schema diff 都淹在 4 万行字面量里。

CREATE TABLE IF NOT EXISTS sys_region
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    region_code VARCHAR(12) NOT NULL COMMENT '国家统计局统计用区划代码：省 2 位 / 市 4 位 / 区县 6 位 / 街道 9 位。**用国标而不自造** —— 地址、快递单、发票、通道进件全按国标走，自造一套迟早要在对外接口上做映射，而映射对不上的症状是「这个地址快递下不了单」，没人会想到根因在区划表',
    parent_code VARCHAR(12) DEFAULT NULL COMMENT '上级区划码。省级为空',
    level VARCHAR(16) NOT NULL COMMENT 'PROVINCE / CITY / DISTRICT / STREET',
    name VARCHAR(64) NOT NULL COMMENT '展示名。取服务端的，端上不再各自维护一份',
    enabled TINYINT(4) NOT NULL DEFAULT 1 COMMENT '开城开关：运营决定哪些区划可被选为经营范围。**停用只影响新选择，存量商家不动** —— 与行业停用同一口径',
    sort INT(11) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_region_code (region_code),
    KEY idx_sys_region_parent (parent_code),
    KEY idx_sys_region_level (level,enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='行政区划：省/市/区县/街道四级，国家统计局口径';
