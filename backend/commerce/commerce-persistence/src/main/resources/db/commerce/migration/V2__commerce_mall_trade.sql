-- Commerce-owned tables only. No host FK or host table mutation.
CREATE TABLE `mall_quote` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `quote_no` VARCHAR(64) NOT NULL,
  `store_no` VARCHAR(64) NOT NULL,
  `entity_no` VARCHAR(64) NOT NULL,
  `buyer_user_no` VARCHAR(64) NOT NULL,
  `intent` VARCHAR(16) NOT NULL,
  `business_type_code` VARCHAR(32) NOT NULL,
  `currency` VARCHAR(8) NOT NULL,
  `valuation_at` BIGINT NOT NULL,
  `expires_at` BIGINT NOT NULL,
  `account_version` BIGINT NOT NULL,
  `credit_account_version` BIGINT NOT NULL,
  `pricing_policy_version` VARCHAR(64) NOT NULL,
  `stacking_policy_version` VARCHAR(64) NOT NULL,
  `request_hash` VARCHAR(128) NOT NULL,
  `line_snapshot` JSON NOT NULL,
  `conversion_snapshot` JSON NOT NULL,
  `schedule_preview_snapshot` JSON NOT NULL,
  `price_minor` BIGINT NOT NULL,
  `new_conversion_minor` BIGINT NOT NULL,
  `credit_used_minor` BIGINT NOT NULL,
  `cash_due_minor` BIGINT NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`quote_no`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `mall_order` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `order_no` VARCHAR(64) NOT NULL,
  `store_no` VARCHAR(64) NOT NULL,
  `entity_no` VARCHAR(64) NOT NULL,
  `buyer_user_no` VARCHAR(64) NOT NULL,
  `business_type_code` VARCHAR(32) NOT NULL,
  `quote_no` VARCHAR(64) NOT NULL,
  `intent` VARCHAR(32) NOT NULL,
  `currency` VARCHAR(8) NOT NULL,
  `gross_minor` BIGINT NOT NULL,
  `discount_minor` BIGINT NOT NULL,
  `deal_minor` BIGINT NOT NULL,
  `credit_used_minor` BIGINT NOT NULL,
  `cash_due_minor` BIGINT NOT NULL,
  `cash_paid_minor` BIGINT NOT NULL,
  `cash_refunded_minor` BIGINT NOT NULL,
  `pay_status` VARCHAR(16) NOT NULL,
  `fulfillment_status` VARCHAR(16) NOT NULL,
  `refund_status` VARCHAR(24) NOT NULL,
  `pay_deadline` BIGINT NOT NULL,
  `paid_at` BIGINT NULL,
  `closed_at` BIGINT NULL,
  `idempotency_key` VARCHAR(128) NOT NULL,
  `request_hash` VARCHAR(128) NOT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`order_no`),
  UNIQUE KEY uk_1 (`tenant_no`,`quote_no`),
  UNIQUE KEY uk_2 (`tenant_no`,`buyer_user_no`,`idempotency_key`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `mall_order_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `order_item_no` VARCHAR(64) NOT NULL,
  `order_no` VARCHAR(64) NOT NULL,
  `sku_no` VARCHAR(64) NOT NULL,
  `sku_version_no` VARCHAR(64) NOT NULL,
  `quantity` INT NOT NULL,
  `unit_price_minor` BIGINT NOT NULL,
  `gross_minor` BIGINT NOT NULL,
  `discount_minor` BIGINT NOT NULL,
  `deal_minor` BIGINT NOT NULL,
  `sku_snapshot` JSON NOT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`order_item_no`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `mall_order_component` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `order_component_no` VARCHAR(64) NOT NULL,
  `order_item_no` VARCHAR(64) NOT NULL,
  `component_no` VARCHAR(64) NOT NULL,
  `component_type` VARCHAR(32) NOT NULL,
  `target_snapshot` JSON NOT NULL,
  `unit_count` INT NOT NULL,
  `allocated_deal_minor` BIGINT NOT NULL,
  `allocated_cash_minor` BIGINT NOT NULL,
  `allocated_credit_minor` BIGINT NOT NULL,
  `eligible_basis_minor` BIGINT NOT NULL,
  `is_gift` TINYINT NOT NULL,
  `fulfillment_status` VARCHAR(16) NOT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`order_component_no`),
  UNIQUE KEY uk_1 (`tenant_no`,`order_item_no`,`component_no`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `mall_payment_ref` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `payment_attempt_no` VARCHAR(64) NOT NULL,
  `order_no` VARCHAR(64) NOT NULL,
  `pay_order_no` VARCHAR(64) NOT NULL,
  `receiver_account_no` VARCHAR(64) NOT NULL,
  `payment_business_type` VARCHAR(32) NOT NULL,
  `currency` VARCHAR(8) NOT NULL,
  `amount_minor` BIGINT NOT NULL,
  `status` VARCHAR(24) NOT NULL,
  `provider_paid_at` BIGINT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`payment_attempt_no`),
  UNIQUE KEY uk_1 (`tenant_no`,`pay_order_no`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `mall_fulfillment_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `task_no` VARCHAR(64) NOT NULL,
  `order_component_no` VARCHAR(64) NOT NULL,
  `unit_seq` INT NOT NULL,
  `task_type` VARCHAR(16) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `attempt_count` INT NOT NULL,
  `next_retry_at` BIGINT NOT NULL,
  `result_no` VARCHAR(64) NULL,
  `last_error_code` VARCHAR(64) NULL,
  `lease_until` BIGINT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`task_no`),
  UNIQUE KEY uk_1 (`tenant_no`,`order_component_no`,`unit_seq`,`task_type`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `mall_shipment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `shipment_no` VARCHAR(64) NOT NULL,
  `order_no` VARCHAR(64) NOT NULL,
  `shipment_items` JSON NOT NULL,
  `address_snapshot` JSON NOT NULL,
  `carrier_code` VARCHAR(32) NULL,
  `tracking_no` VARCHAR(128) NULL,
  `status` VARCHAR(24) NOT NULL,
  `shipped_at` BIGINT NULL,
  `signed_at` BIGINT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`shipment_no`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `mall_refund` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `refund_no` VARCHAR(64) NOT NULL,
  `order_no` VARCHAR(64) NOT NULL,
  `store_no` VARCHAR(64) NOT NULL,
  `reason` VARCHAR(1024) NOT NULL,
  `policy_snapshot` JSON NOT NULL,
  `requested_cash_minor` BIGINT NOT NULL,
  `approved_cash_minor` BIGINT NOT NULL,
  `restore_credit_minor` BIGINT NOT NULL,
  `pay_refund_no` VARCHAR(64) NULL,
  `status` VARCHAR(24) NOT NULL,
  `idempotency_key` VARCHAR(128) NOT NULL,
  `reviewed_by` VARCHAR(64) NULL,
  `reviewed_at` BIGINT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`refund_no`),
  UNIQUE KEY uk_1 (`tenant_no`,`order_no`,`idempotency_key`),
  UNIQUE KEY uk_2 (`tenant_no`,`pay_refund_no`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `mall_refund_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `refund_item_no` VARCHAR(64) NOT NULL,
  `refund_no` VARCHAR(64) NOT NULL,
  `order_component_no` VARCHAR(64) NOT NULL,
  `period_no` VARCHAR(64) NULL,
  `quantity` INT NOT NULL,
  `cash_refund_minor` BIGINT NOT NULL,
  `credit_restore_minor` BIGINT NOT NULL,
  `rights_action` VARCHAR(24) NOT NULL,
  `status` VARCHAR(24) NOT NULL,
  `adjustment_snapshot` JSON NOT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`refund_item_no`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `commerce_event_inbox` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `source_system` VARCHAR(32) NOT NULL,
  `event_id` VARCHAR(128) NOT NULL,
  `event_type` VARCHAR(64) NOT NULL,
  `aggregate_no` VARCHAR(64) NOT NULL,
  `payload_hash` VARCHAR(128) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `received_at` BIGINT NOT NULL,
  `processed_at` BIGINT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`source_system`,`event_id`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE INDEX idx_0 ON `mall_quote` (`tenant_no`,`store_no`,`created_at`);

CREATE INDEX idx_1 ON `mall_quote` (`tenant_no`,`status`,`expires_at`);

CREATE INDEX idx_2 ON `mall_order` (`tenant_no`,`store_no`,`created_at`,`id`);

CREATE INDEX idx_3 ON `mall_order` (`tenant_no`,`pay_status`,`pay_deadline`);

CREATE INDEX idx_4 ON `mall_payment_ref` (`tenant_no`,`order_no`,`created_at`);

CREATE INDEX idx_5 ON `mall_fulfillment_task` (`tenant_no`,`status`,`next_retry_at`);

CREATE INDEX idx_6 ON `mall_shipment` (`tenant_no`,`order_no`);

CREATE INDEX idx_7 ON `mall_refund` (`tenant_no`,`status`,`updated_at`);

CREATE INDEX idx_8 ON `mall_refund_item` (`tenant_no`,`order_component_no`,`status`);
