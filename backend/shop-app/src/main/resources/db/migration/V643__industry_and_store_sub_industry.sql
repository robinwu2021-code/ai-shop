-- 平台行业与子行业（TDD-子行业与能力端验收 B1–B7）。
--
-- App 建店按「行业 → 子行业 → 经营类型」两级选（GET /biz/merchant/store-setup）。此前后端只给一对一占位
-- （每个经营类型当一个行业、下挂同名子行业），门店也不存选了哪个子行业。
-- 子行业只决定建店的经营类型并用于分类展示，**不影响方案与功能**（方案仍按经营类型）。
-- 经营类型 SERVICE 由运营在运营端建并发布；未发布前服务业子行业不出现在 store-setup。
-- 行业本身那一行的 sub_industry_code 存空串（唯一键里不用 NULL）。
-- 带 DDL：166 应用库账号无建表 / 改表权限，需 root 先执行或部署时临时授权。

CREATE TABLE sys_store_industry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    industry_code VARCHAR(32) NOT NULL,
    sub_industry_code VARCHAR(48) NOT NULL DEFAULT '',
    business_type_code VARCHAR(32) NOT NULL,
    name_zh VARCHAR(32) NOT NULL,
    name_tw VARCHAR(32) NOT NULL,
    name_en VARCHAR(64) NULL,
    sort INT NOT NULL DEFAULT 0,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_store_industry (tenant_no, industry_code, sub_industry_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台行业与子行业：子行业决定建店经营类型，不影响方案';

INSERT INTO sys_store_industry (tenant_no, industry_code, sub_industry_code, business_type_code, name_zh, name_tw, name_en,
                          sort, enabled, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('MAIN', 'FOOD', '', 'FOOD', '餐饮', '餐飲', 'Food & beverage', 10, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'FOOD', 'FOOD_BREAKFAST', 'FOOD', '早餐店', '早餐店', 'Breakfast', 10, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'FOOD', 'FOOD_CHINESE', 'FOOD', '中式餐馆', '中式餐館', 'Chinese restaurant', 20, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'FOOD', 'FOOD_FASTFOOD', 'FOOD', '快餐便当', '快餐便當', 'Fast food & bento', 30, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'FOOD', 'FOOD_SNACK', 'FOOD', '小吃', '小吃', 'Snacks', 40, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'FOOD', 'FOOD_DRINK', 'FOOD', '手摇饮料', '手搖飲料', 'Tea & drinks', 50, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'FOOD', 'FOOD_CAFE', 'FOOD', '咖啡厅', '咖啡廳', 'Cafe', 60, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'FOOD', 'FOOD_BAKERY', 'FOOD', '烘焙甜点', '烘焙甜點', 'Bakery & desserts', 70, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'FOOD', 'FOOD_HOTPOT', 'FOOD', '火锅', '火鍋', 'Hot pot', 80, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'FOOD', 'FOOD_BBQ', 'FOOD', '烧烤', '燒烤', 'Barbecue', 90, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'FOOD', 'FOOD_JPKR', 'FOOD', '日韩料理', '日韓料理', 'Japanese & Korean', 100, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'FOOD', 'FOOD_WESTERN', 'FOOD', '西餐', '西餐', 'Western', 110, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'FOOD', 'FOOD_BAR', 'FOOD', '酒吧', '酒吧', 'Bar', 120, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'RETAIL', '', 'RETAIL', '零售', '零售', 'Retail', 20, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'RETAIL', 'RETAIL_CVS', 'RETAIL', '便利店 / 超市', '便利商店 / 超市', 'Convenience & supermarket', 10, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'RETAIL', 'RETAIL_FRESH', 'RETAIL', '生鲜蔬果', '生鮮蔬果', 'Fresh produce', 20, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'RETAIL', 'RETAIL_FASHION', 'RETAIL', '服饰鞋包', '服飾鞋包', 'Apparel & bags', 30, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'RETAIL', 'RETAIL_COSMETIC', 'RETAIL', '美妆个护', '美妝個護', 'Beauty & personal care', 40, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'RETAIL', 'RETAIL_BABY', 'RETAIL', '母婴用品', '母嬰用品', 'Mother & baby', 50, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'RETAIL', 'RETAIL_DIGITAL', 'RETAIL', '数码家电', '數位家電', 'Electronics', 60, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'RETAIL', 'RETAIL_DAILY', 'RETAIL', '日用百货', '日用百貨', 'Household goods', 70, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'RETAIL', 'RETAIL_PET', 'RETAIL', '宠物用品', '寵物用品', 'Pet supplies', 80, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'RETAIL', 'RETAIL_BOOK', 'RETAIL', '书店文具', '書店文具', 'Books & stationery', 90, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'RETAIL', 'RETAIL_DRUG', 'RETAIL', '药妆保健', '藥妝保健', 'Pharmacy & health', 100, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'SERVICE', '', 'SERVICE', '服务业', '服務業', 'Services', 30, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'SERVICE', 'SERVICE_HAIR', 'SERVICE', '美发', '美髮', 'Hair salon', 10, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'SERVICE', 'SERVICE_NAIL', 'SERVICE', '美甲美睫', '美甲美睫', 'Nails & lashes', 20, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'SERVICE', 'SERVICE_SKIN', 'SERVICE', '美容护肤', '美容護膚', 'Beauty & skincare', 30, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'SERVICE', 'SERVICE_SPA', 'SERVICE', '按摩 SPA', '按摩 SPA', 'Massage & spa', 40, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'SERVICE', 'SERVICE_FITNESS', 'SERVICE', '健身瑜伽', '健身瑜伽', 'Fitness & yoga', 50, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'SERVICE', 'SERVICE_PET', 'SERVICE', '宠物美容', '寵物美容', 'Pet grooming', 60, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'SERVICE', 'SERVICE_LAUNDRY', 'SERVICE', '洗衣', '洗衣', 'Laundry', 70, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'SERVICE', 'SERVICE_REPAIR', 'SERVICE', '维修', '維修', 'Repair', 80, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'SERVICE', 'SERVICE_EDU', 'SERVICE', '教育培训', '教育培訓', 'Education', 90, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('MAIN', 'SERVICE', 'SERVICE_PHOTO', 'SERVICE', '摄影', '攝影', 'Photography', 100, 1, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);

-- 门店选了哪个行业、子行业（选「其他」的那一级不存码，文字在 industry_text / sub_industry_text）
ALTER TABLE mch_store ADD COLUMN industry_code VARCHAR(32) NULL;

ALTER TABLE mch_store ADD COLUMN sub_industry_code VARCHAR(48) NULL;
