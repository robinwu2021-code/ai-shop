-- 商品模块改版：成本价与详情图。
--
-- cost_price：商家自己的进货成本，**只在 B 端出现** —— 定价时算毛利靠它。
-- 允许高于售价（引流款本来就可能亏本卖），所以不加任何 CHECK。
ALTER TABLE prd_sku ADD COLUMN cost_price BIGINT(20) DEFAULT NULL COMMENT '成本价（最小货币单位）。仅商家侧可见，不下发买家端';

-- detail_images：详情正文下方竖排的长图，**与 images（顶部轮播方图）分开存**。
-- 合成一个数组的话，C 端只能靠宽高比猜哪几张该轮播、哪几张该竖排。
ALTER TABLE prd_goods ADD COLUMN detail_images TEXT DEFAULT NULL COMMENT 'JSON 数组：图文详情区的长图，按顺序全宽竖排';
