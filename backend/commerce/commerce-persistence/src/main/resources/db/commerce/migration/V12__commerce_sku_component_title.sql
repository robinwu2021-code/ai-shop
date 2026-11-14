-- Optional display name per SKU component (e.g. bundle parts "零售專業版・年付" / "熱敏印表機 80mm").
-- Frozen with the SKU version like the other component columns; NULL = no name (clients fall back to kind).
ALTER TABLE `mall_sku_component` ADD COLUMN `title` VARCHAR(64) NULL AFTER `activation_policy`;
