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
  `refund_channel` VARCHAR(16) NOT NULL,
  `payment_no` VARCHAR(64) NULL,
  `command_snapshot` JSON NOT NULL,
  `verified_fact_snapshot` JSON NULL,
  `approved_review_quote_no` VARCHAR(64) NULL,
  `review_no` VARCHAR(64) NULL,
  `reviewed_at` BIGINT NULL,
  UNIQUE KEY uk_0 (`tenant_no`,`refund_no`),
  KEY idx_order (`tenant_no`,`order_no`),
  UNIQUE KEY uk_2 (`tenant_no`,`pay_refund_no`),
  PRIMARY KEY (`id`),
  CHECK (deleted = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

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
  KEY ix_refund_items (`tenant_no`,`refund_no`),
  UNIQUE KEY uk_0 (`tenant_no`,`refund_item_no`),
  PRIMARY KEY (`id`),
  CHECK (deleted = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Terminal payouts release the payment slot while retaining the original receipt and attempts.
CREATE UNIQUE INDEX uk_mall_refund_active_payout ON mall_refund
(tenant_no, (CASE WHEN command_snapshot->>'$.offlinePayout.status'
   IN ('PREPARED','AWAITING_PAYOUT','PAYOUT_ACCEPTED') THEN payment_no ELSE NULL END));
