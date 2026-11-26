-- 设计草案之外、发票代码需要的补充。同时是给 192.168.3.61 上 n_service 的修改建议，尚未在 61 执行。
-- 依据见 TDD-多数据源与发票打印对齐新库 §2.7（F1、F2）与 §8（Q3、Q7）。

-- F1 代码依赖唯一性：占位写入用 ON DUPLICATE KEY，一个来源只能有一张申请；一个主体一种税制一行。
ALTER TABLE `ivc_invoice_application` DROP INDEX `uk_ivc_application_no`;
ALTER TABLE `ivc_invoice_application` ADD UNIQUE KEY `uk_ivc_application_no` (`tenant_no`, `application_no`);
ALTER TABLE `ivc_invoice_application` DROP INDEX `uk_ivc_application_source`;
ALTER TABLE `ivc_invoice_application` ADD UNIQUE KEY `uk_ivc_application_source` (`tenant_no`, `entity_no`, `source_type`, `source_no`);
ALTER TABLE `ivc_regime_profile` DROP INDEX `uk_ivc_regime_profile`;
ALTER TABLE `ivc_regime_profile` ADD UNIQUE KEY `uk_ivc_regime_profile` (`tenant_no`, `entity_no`, `regime_code`);

-- F2 通道账户的 QR 金钥引用（证明联左码加密用），与服务商凭据引用 sec_key_id 同一写法。
ALTER TABLE `ivc_channel_account` ADD COLUMN `qr_sec_key_id` varchar(32) DEFAULT NULL COMMENT 'QR 金钥在密钥库的引用' AFTER `sec_key_id`;

-- Q3 门店开票设置：设计里没有这张表，B 端 store-settings 与 App 的发票设定页在用。
CREATE TABLE `ivc_store_setting` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
  `tenant_no` varchar(32) NOT NULL COMMENT '租户号',
  `entity_no` varchar(32) NOT NULL COMMENT '主体号',
  `store_no` varchar(32) NOT NULL COMMENT '门店号',
  `default_tax_type` varchar(16) DEFAULT NULL COMMENT '默认课税别：TAXABLE 应税 / ZERO_RATED 零税率 / EXEMPT 免税',
  `invoice_timing` varchar(16) NOT NULL COMMENT '开票时机：ON_PAID 付款后 / MANUAL 手动 / LATER 稍后',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `created_by` varchar(32) DEFAULT NULL COMMENT '创建人',
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `updated_by` varchar(32) DEFAULT NULL COMMENT '更新人',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ivc_store_setting` (`tenant_no`, `entity_no`, `store_no`),
  CONSTRAINT `ck_ivc_store_setting` CHECK ((`default_tax_type` IS NULL OR `default_tax_type` IN ('TAXABLE','ZERO_RATED','EXEMPT')) AND `invoice_timing` IN ('ON_PAID','MANUAL','LATER'))
) COMMENT='门店开票设置：默认课税别与开票时机';
