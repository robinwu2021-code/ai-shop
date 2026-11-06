-- Merchant-mall category tree (max 3 levels, enforced by CategoryService). mall_product.category_code
-- keeps its column and now references mall_category.category_code (checked on product save).
CREATE TABLE `mall_category` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `category_code` VARCHAR(32) NOT NULL,
  `parent_code` VARCHAR(32) NULL,
  `level` INT NOT NULL,
  `names` JSON NOT NULL,
  `icon` VARCHAR(512) NULL,
  `sort_order` INT NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  UNIQUE KEY uk_mall_category (`tenant_no`,`category_code`),
  KEY idx_mall_category_parent (`tenant_no`,`parent_code`),
  PRIMARY KEY (`id`),
  CHECK (deleted IN (0,1)),
  CHECK (`level` BETWEEN 1 AND 3),
  CHECK (`status` IN ('ENABLED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Existing free-text product categories become enabled level-1 categories named by their code,
-- so every stored product stays valid; operators rename them afterwards.
INSERT INTO `mall_category`
  (`tenant_no`,`created_at`,`updated_at`,`created_by`,`updated_by`,`version`,`deleted`,
   `category_code`,`parent_code`,`level`,`names`,`icon`,`sort_order`,`status`)
SELECT p.tenant_no, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'migration:V11', 'migration:V11', 1, 0,
       p.category_code, NULL, 1,
       JSON_OBJECT('zh-CN', p.category_code, 'zh-TW', p.category_code, 'en', p.category_code),
       NULL, 0, 'ENABLED'
FROM (SELECT DISTINCT tenant_no, category_code FROM `mall_product` WHERE deleted = 0) p;
