-- 商品级运费模板（ADR-031 §2.4，TDD-下单按门店拆单与运费模板 期 D）。
--
-- 下单时运费模板逐行解析：商品指定的模板 ＞ 所属门店快递通道的模板 ＞ 平台默认模板。
-- 只能从平台模板里选（ful_freight_template，运营维护）；空 = 跟随门店。
-- 指定的模板被运营归档后，下单回落门店模板，不回落成 0 元。
ALTER TABLE prd_goods ADD COLUMN freight_template_no VARCHAR(32) DEFAULT NULL COMMENT '商品指定的运费模板号；空=跟随门店';
