-- 海外收货地址（TDD-C端收货地址-录入与定位重排 · M6）。
--
-- 表此前假设「省市区 + 高德能搜到的地点」：没有国家/地区、没有邮编、
-- 手机号在端上写死 11 位数字。于是海外地址**填不了** —— 不是填得难看，是存不下。
--
-- 三列都有默认值，**存量行天然是 CN / 86，不需要回填**。
-- 旧版本 App 不发这三个字段，行为一个字不变。
ALTER TABLE usr_address ADD COLUMN country_code CHAR(2) NOT NULL DEFAULT 'CN' COMMENT 'ISO 3166-1 两位码。非 CN 时端上整段换形状：关掉地点搜索/附近/地图选点/省市区拆分';
ALTER TABLE usr_address ADD COLUMN postal_code VARCHAR(16) DEFAULT NULL COMMENT '邮编。中国大陆不用，海外多数国家必填';
ALTER TABLE usr_address ADD COLUMN phone_cc VARCHAR(8) NOT NULL DEFAULT '86' COMMENT '手机国家区号（不带 +）。位数校验按国家放宽，不再写死 11 位';
