-- 商品记不记库存（TDD-商品纳入进销存开关 §3，第一期）。
--
-- 两级设置，单品优先于品类，都没设用平台默认（实物 / 生鲜记，服务 / 券 / 虚拟不记）：
--   prd_goods.inv_mode              单品：INHERIT 跟随品类 / ON 记库存 / OFF 不记库存
--   prd_entity_category_inv         品类：本主体给某个类目设过「记 / 不记」；没有行 = 用平台默认
--
-- 挂主体不挂门店：记不记决定的是这件商品在进销存里有没有物料，物料属于主体。
-- 存量一律 INHERIT —— 实物 / 生鲜的行为与今天相同；服务类已建的物料由上线后的回填处理，不在迁移里动。

ALTER TABLE prd_goods
    ADD COLUMN inv_mode VARCHAR(8) NOT NULL DEFAULT 'INHERIT' COMMENT '记不记库存：INHERIT 跟随品类 / ON 记 / OFF 不记';

CREATE TABLE IF NOT EXISTS prd_entity_category_inv
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL COMMENT '主体',
    category_no VARCHAR(64) NOT NULL COMMENT '类目',
    managed TINYINT(4) NOT NULL COMMENT '1 记库存 / 0 不记',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_prd_entity_category_inv (entity_no, category_no)
) COMMENT='主体按类目设置记不记库存。稀疏：没有行 = 平台默认。行只改不删，唯一键不含 deleted';
