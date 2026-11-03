-- Commerce-owned tables only. No host FK or host table mutation.
CREATE TABLE `mch_subscription_account` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `store_no` VARCHAR(64) NOT NULL,
  `entity_no` VARCHAR(64) NOT NULL,
  `business_type_code` VARCHAR(32) NOT NULL,
  `entitlement_version` BIGINT NOT NULL,
  `pending_change_order_no` VARCHAR(64) NULL,
  `legacy_contract_ref` VARCHAR(64) NULL,
  `migration_batch_no` VARCHAR(64) NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`store_no`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `mch_subscription_period` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `period_no` VARCHAR(64) NOT NULL,
  `store_no` VARCHAR(64) NOT NULL,
  `entity_no` VARCHAR(64) NOT NULL,
  `business_type_code` VARCHAR(32) NOT NULL,
  `stream_key` VARCHAR(128) NOT NULL,
  `source_type` VARCHAR(24) NOT NULL,
  `source_ref_no` VARCHAR(64) NOT NULL,
  `fulfillment_task_no` VARCHAR(64) NULL,
  `plan_no` VARCHAR(64) NULL,
  `plan_version_no` VARCHAR(64) NULL,
  `standalone_entitlement_code` VARCHAR(64) NULL,
  `tier_code` VARCHAR(16) NULL,
  `delivered_at` BIGINT NOT NULL,
  `delivery_seq` BIGINT NOT NULL,
  `granted_duration_ms` BIGINT NULL,
  `revoked_duration_ms` BIGINT NOT NULL,
  `original_start_at` BIGINT NOT NULL,
  `original_end_at` BIGINT NULL,
  `effective_start_at` BIGINT NOT NULL,
  `effective_end_at` BIGINT NULL,
  `eligible_basis_minor` BIGINT NOT NULL,
  `cash_basis_minor` BIGINT NOT NULL,
  `credit_basis_minor` BIGINT NOT NULL,
  `currency` VARCHAR(8) NOT NULL,
  `state` VARCHAR(24) NOT NULL,
  `converted_by_no` VARCHAR(64) NULL,
  `source_policy_snapshot` JSON NOT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`period_no`),
  UNIQUE KEY uk_1 (`tenant_no`,`fulfillment_task_no`),
  UNIQUE KEY uk_2 (`tenant_no`,`source_type`,`source_ref_no`,`stream_key`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `mch_store_end_grant` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `grant_no` VARCHAR(64) NOT NULL,
  `store_no` VARCHAR(64) NOT NULL,
  `period_no` VARCHAR(64) NOT NULL,
  `end_code` VARCHAR(64) NOT NULL,
  `entitlement_code` VARCHAR(64) NOT NULL,
  `schedule_revision` BIGINT NOT NULL,
  `segment_seq` INT NOT NULL,
  `schedule_event_no` VARCHAR(64) NOT NULL,
  `start_at` BIGINT NOT NULL,
  `end_at` BIGINT NULL,
  `state` VARCHAR(16) NOT NULL,
  `revoked_at` BIGINT NULL,
  `reason` VARCHAR(512) NOT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`grant_no`),
  UNIQUE KEY uk_1 (`tenant_no`,`period_no`,`entitlement_code`,`schedule_revision`,`segment_seq`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `mch_subscription_reservation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `reservation_no` VARCHAR(64) NOT NULL,
  `store_no` VARCHAR(64) NOT NULL,
  `order_no` VARCHAR(64) NOT NULL,
  `quote_no` VARCHAR(64) NOT NULL,
  `subscription_version` BIGINT NOT NULL,
  `credit_account_version` BIGINT NOT NULL,
  `valuation_at` BIGINT NOT NULL,
  `pay_deadline` BIGINT NOT NULL,
  `status` VARCHAR(24) NOT NULL,
  `source_periods_snapshot` JSON NOT NULL,
  `reserved_credit_minor` BIGINT NOT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`reservation_no`),
  UNIQUE KEY uk_1 (`tenant_no`,`order_no`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `mch_subscription_conversion` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `conversion_no` VARCHAR(64) NOT NULL,
  `store_no` VARCHAR(64) NOT NULL,
  `order_no` VARCHAR(64) NOT NULL,
  `valuation_at` BIGINT NOT NULL,
  `effective_at` BIGINT NOT NULL,
  `new_period_no` VARCHAR(64) NOT NULL,
  `currency` VARCHAR(8) NOT NULL,
  `converted_minor` BIGINT NOT NULL,
  `credit_consumed_minor` BIGINT NOT NULL,
  `cash_paid_minor` BIGINT NOT NULL,
  `policy_version` VARCHAR(64) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`conversion_no`),
  UNIQUE KEY uk_1 (`tenant_no`,`order_no`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `mch_subscription_conversion_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `conversion_no` VARCHAR(64) NOT NULL,
  `source_period_no` VARCHAR(64) NOT NULL,
  `basis_minor` BIGINT NOT NULL,
  `original_duration_ms` BIGINT NOT NULL,
  `remaining_duration_ms` BIGINT NOT NULL,
  `converted_minor` BIGINT NOT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`conversion_no`,`source_period_no`),
  UNIQUE KEY uk_1 (`tenant_no`,`source_period_no`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `mch_subscription_credit_account` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `store_no` VARCHAR(64) NOT NULL,
  `currency` VARCHAR(8) NOT NULL,
  `balance_minor` BIGINT NOT NULL,
  `reserved_minor` BIGINT NOT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`store_no`,`currency`),
  PRIMARY KEY (`id`),
  CHECK (reserved_minor >= 0 AND balance_minor >= reserved_minor)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `mch_subscription_credit_lot` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `credit_lot_no` VARCHAR(64) NOT NULL,
  `store_no` VARCHAR(64) NOT NULL,
  `currency` VARCHAR(8) NOT NULL,
  `source_type` VARCHAR(24) NOT NULL,
  `source_ref_no` VARCHAR(64) NOT NULL,
  `issued_minor` BIGINT NOT NULL,
  `remaining_minor` BIGINT NOT NULL,
  `reserved_minor` BIGINT NOT NULL,
  `parent_provenance` JSON NOT NULL,
  `expires_at` BIGINT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`credit_lot_no`),
  UNIQUE KEY uk_1 (`tenant_no`,`source_type`,`source_ref_no`),
  PRIMARY KEY (`id`),
  CHECK (reserved_minor >= 0 AND remaining_minor >= reserved_minor AND issued_minor >= remaining_minor)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `mch_subscription_credit_ledger` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `ledger_no` VARCHAR(64) NOT NULL,
  `store_no` VARCHAR(64) NOT NULL,
  `currency` VARCHAR(8) NOT NULL,
  `credit_lot_no` VARCHAR(64) NOT NULL,
  `event_type` VARCHAR(32) NOT NULL,
  `biz_ref_no` VARCHAR(64) NOT NULL,
  `event_seq` INT NOT NULL,
  `amount_minor` BIGINT NOT NULL,
  `balance_delta_minor` BIGINT NOT NULL,
  `reserved_delta_minor` BIGINT NOT NULL,
  `order_component_no` VARCHAR(64) NULL,
  `reversal_of_ledger_no` VARCHAR(64) NULL,
  `reason` VARCHAR(1024) NOT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`ledger_no`),
  UNIQUE KEY uk_1 (`tenant_no`,`event_type`,`biz_ref_no`,`credit_lot_no`,`event_seq`),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE INDEX idx_9 ON `mch_subscription_period` (`tenant_no`,`store_no`,`stream_key`,`state`,`effective_start_at`,`effective_end_at`);

CREATE INDEX idx_10 ON `mch_store_end_grant` (`tenant_no`,`store_no`,`entitlement_code`,`state`,`start_at`,`end_at`);

CREATE INDEX idx_11 ON `mch_subscription_reservation` (`tenant_no`,`status`,`pay_deadline`);

CREATE INDEX idx_12 ON `mch_subscription_credit_lot` (`tenant_no`,`store_no`,`currency`,`expires_at`,`id`);

CREATE INDEX idx_13 ON `mch_subscription_credit_ledger` (`tenant_no`,`store_no`,`created_at`,`id`);

CREATE INDEX idx_14 ON `mch_subscription_credit_ledger` (`tenant_no`,`order_component_no`);
