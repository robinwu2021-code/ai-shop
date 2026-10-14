-- 商品级限购地区（设计-发布与销售地区优化 #3）。
--
-- 这件货「不卖到哪些省」。此前识别出的 excludeRegionText 只跳转、不落库、不展示;
-- 「限购地区」唯一落库点是运费模板(省名·整店·仅快递)——没有商品级字段,补上。
--
-- 省级 regionCode 的 JSON 数组,排除语义:默认全国可售,列表内不可售，空或 null 为全国。
-- 用码不用省名:对齐 sys_region.region_code 省级两位码,下单按收货地址取前两位匹配。
-- VARCHAR(255):省级最多 34 个,JSON 约 175 字符,够用;生产主库 MySQL 9.7,禁 uca1400。
ALTER TABLE prd_goods ADD COLUMN restricted_regions VARCHAR(255) DEFAULT NULL COMMENT '限购地区:不卖到的省级 regionCode JSON 数组，空=全国可售';
