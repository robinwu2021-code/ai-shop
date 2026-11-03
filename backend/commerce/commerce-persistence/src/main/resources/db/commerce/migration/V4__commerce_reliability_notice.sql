-- Commerce-owned tables only. No host FK or host table mutation.
CREATE TABLE `commerce_outbox` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `event_no` VARCHAR(64) NOT NULL,
  `aggregate_type` VARCHAR(32) NOT NULL,
  `aggregate_no` VARCHAR(64) NOT NULL,
  `aggregate_version` BIGINT NOT NULL,
  `event_type` VARCHAR(64) NOT NULL,
  `payload` JSON NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `retry_count` INT NOT NULL,
  `next_retry_at` BIGINT NOT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`event_no`),
  UNIQUE KEY uk_1 (`tenant_no`,`aggregate_type`,`aggregate_no`,`aggregate_version`,`event_type`),
  PRIMARY KEY (`id`),
  `schema_version` INT NOT NULL,
  `occurred_at` BIGINT NOT NULL,
  `published_at` BIGINT NULL,
  CHECK (deleted = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `mch_subscription_migration_map` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `migration_batch_no` VARCHAR(64) NOT NULL,
  `legacy_entity_plan_id` BIGINT NOT NULL,
  `legacy_contract_ref` VARCHAR(64) NOT NULL,
  `store_no` VARCHAR(64) NOT NULL,
  `new_period_no` VARCHAR(64) NOT NULL,
  `allocated_basis_minor` BIGINT NOT NULL,
  `evidence_ref` VARCHAR(256) NULL,
  `legacy_snapshot` JSON NOT NULL,
  `validation_status` VARCHAR(24) NOT NULL,
  `cutover_at` BIGINT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`legacy_entity_plan_id`,`store_no`,`migration_batch_no`),
  UNIQUE KEY uk_1 (`tenant_no`,`new_period_no`),
  PRIMARY KEY (`id`),
  CHECK (deleted = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `mch_end_schedule_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `schedule_event_no` VARCHAR(64) NOT NULL,
  `store_no` VARCHAR(64) NOT NULL,
  `entitlement_code` VARCHAR(64) NOT NULL,
  `entitlement_version` BIGINT NOT NULL,
  `operation_ref_no` VARCHAR(64) NOT NULL,
  `reason_type` VARCHAR(24) NOT NULL,
  `effective_at` BIGINT NOT NULL,
  `before_snapshot` JSON NOT NULL,
  `after_snapshot` JSON NOT NULL,
  `duration_check_snapshot` JSON NOT NULL,
  `stacking_policy_version` VARCHAR(64) NOT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`schedule_event_no`),
  UNIQUE KEY uk_1 (`tenant_no`,`store_no`,`entitlement_code`,`entitlement_version`),
  UNIQUE KEY uk_2 (`tenant_no`,`operation_ref_no`,`entitlement_code`),
  PRIMARY KEY (`id`),
  CHECK (deleted = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE `commerce_expiry_notice` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tenant_no` VARCHAR(32) NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  `created_by` VARCHAR(64) NULL,
  `updated_by` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL,
  `deleted` TINYINT NOT NULL,
  `notice_no` VARCHAR(64) NOT NULL,
  `store_no` VARCHAR(64) NOT NULL,
  `entitlement_code` VARCHAR(64) NOT NULL,
  `coverage_end_at` BIGINT NOT NULL,
  `threshold_code` VARCHAR(16) NOT NULL,
  `due_at` BIGINT NOT NULL,
  `expires_at` BIGINT NOT NULL,
  `policy_version` VARCHAR(64) NOT NULL,
  `observed_entitlement_version` BIGINT NOT NULL,
  `recipient_type` VARCHAR(32) NOT NULL,
  `recipient_no` VARCHAR(64) NOT NULL,
  `channel` VARCHAR(16) NOT NULL,
  `dedup_key` VARCHAR(128) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `attempts` INT NOT NULL,
  `next_attempt_at` BIGINT NULL,
  `lease_until` BIGINT NULL,
  `provider_message_no` VARCHAR(128) NULL,
  `last_error_code` VARCHAR(64) NULL,
  `sent_at` BIGINT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`notice_no`),
  UNIQUE KEY uk_1 (`tenant_no`,`dedup_key`),
  PRIMARY KEY (`id`),
  CHECK (deleted = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE INDEX idx_15 ON `commerce_outbox` (`tenant_no`,`status`,`next_retry_at`);

CREATE INDEX idx_16 ON `commerce_expiry_notice` (`tenant_no`,`status`,`next_attempt_at`);

CREATE INDEX idx_17 ON `commerce_expiry_notice` (`tenant_no`,`store_no`,`entitlement_code`,`coverage_end_at`);

CREATE INDEX idx_18 ON `commerce_expiry_notice` (`tenant_no`,`status`,`lease_until`);
