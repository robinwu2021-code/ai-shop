-- SKU 的**外部身份**：条码 / 商家自有货号 / 计量单位。
--
-- ERP 对接的卡点不在规格模型，在**SKU 有没有稳定且外部可识别的身份**。
-- prd_sku 现在的全部标识只有平台自己生成的 sku_no —— 商家的 ERP、收银秤、
-- 供应商发货单，没有一个认识它，对接第一步就断在这里。
--
-- ⚠️ barcode 此前只存在于 prd_spu_std（标准品），那是 **SPU 级**的；
-- 而条码在真实零售里是 **SKU 级**：同一款饼干的 100g 装与 300g 装是两个条码。
--
-- 三条设计约束（TDD-规格与SKU模型 §5）：
-- 1) 都可空，且**空是常态** —— 生鲜、现做熟食、手工品本来就没有条码，
--    与 prd_spu_std.barcode 同一口径。
-- 2) 货号在**商家自己的命名空间**里唯一，不跨商家；
--    而 barcode **不能做唯一键** —— 同一包饼干在十家店都有，
--    它是查找键不是身份键。做成唯一键的话，第二家店录同一个条码就插不进去。
-- 3) 计量单位是称重品与计件品的分界，也是导出给 ERP 时必须带的一列。
ALTER TABLE prd_sku
    ADD COLUMN IF NOT EXISTS barcode VARCHAR(32) DEFAULT NULL
        COMMENT 'EAN-13 / UPC。与 ERP、收银秤、供应商的通用键。可空且空是常态',
    ADD COLUMN IF NOT EXISTS merchant_sku_code VARCHAR(64) DEFAULT NULL
        COMMENT '商家自有货号。他 ERP 里的主键，对账靠它',
    ADD COLUMN IF NOT EXISTS sale_unit VARCHAR(16) DEFAULT NULL
        COMMENT '计量单位（件/斤/kg/份）。称重品与计件品的分界';

-- 货号在商家自己的命名空间里唯一。**允许多行 NULL**（MySQL/MariaDB 的唯一索引里
-- NULL 互不相等），这正好是我们要的：没填货号的 SKU 有的是。
CREATE UNIQUE INDEX IF NOT EXISTS uk_sku_merchant_code
    ON prd_sku (tenant_no, entity_no, merchant_sku_code);

-- 条码只建**普通索引**：它是查找键（扫码找货），不是身份键。
CREATE INDEX IF NOT EXISTS idx_sku_barcode ON prd_sku (tenant_no, barcode);
