-- 商品销售方式（TDD-商品仅活动可售）。
-- NORMAL = 正常售卖（默认，存量全部落在这里，行为逐字不变）；ACTIVITY_ONLY = 仅活动。
-- 枚举而不是布尔：下一个很可能是「仅会员」，别再加一列。
ALTER TABLE prd_goods ADD COLUMN sale_mode VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '销售方式：NORMAL 正常售卖 / ACTIVITY_ONLY 仅活动';
