-- 门店配送规则（B-11.5 / 契约 DeliveryRule）。
--
-- 挂在**门店**而不是主体：配送半径是从门店门口量出去的，多门店的商家
-- 各店覆盖不同的小区。挂主体的话，第二家店一开，两边的范围就都是错的。
--
-- 四个字段全部给默认值而不是 NULL：配送规则「没配过」和「配成 0」在业务上
-- 是两件事，但端上都得渲染出一个数。给默认值让「没配过」有一个可解释的含义 ——
-- 半径 3 公里、不设起送价、不收配送费，也就是「先跑起来再说」。
ALTER TABLE mch_store
    ADD COLUMN delivery_radius_m INT(11) NOT NULL DEFAULT 3000
        COMMENT '配送半径（米）。默认 3km' AFTER address;

ALTER TABLE mch_store
    ADD COLUMN delivery_min_order_minor BIGINT(20) NOT NULL DEFAULT 0
        COMMENT '起送价（分）。0 = 不设门槛' AFTER delivery_radius_m;

ALTER TABLE mch_store
    ADD COLUMN delivery_fee_minor BIGINT(20) NOT NULL DEFAULT 0
        COMMENT '配送费（分）。0 = 免费送' AFTER delivery_min_order_minor;

ALTER TABLE mch_store
    ADD COLUMN delivery_free_threshold_minor BIGINT(20) NOT NULL DEFAULT 0
        COMMENT '免配送费门槛（分）。0 = 不免' AFTER delivery_fee_minor;

-- 一条 ALTER 一列，而不是一条 ALTER 加四列：SchemaDriftTest 的解析器按
-- 「ALTER TABLE x ADD COLUMN y」逐条抽列，一条语句里的第二列起会被漏掉 ——
-- 漏掉的表现是「迁移与 schema-test.sql 不一致」，而真因在这里。
