-- Beauty V9: separate manual source observation; V1-V8 remain frozen.
-- Old financial inserts keep LEGACY_FUNDED defaults; no historical source relabeling.

ALTER TABLE `bty_source_observation`
    ADD COLUMN `admission_kind` VARCHAR(24) COLLATE utf8mb4_bin NOT NULL DEFAULT 'LEGACY_FUNDED',
    ADD COLUMN `customer_kind` VARCHAR(8) COLLATE utf8mb4_bin NULL,
    ADD COLUMN `guest_no` VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN `identity_revision` BIGINT NULL,
    MODIFY COLUMN `buyer_no` VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN `manual_epoch` BIGINT NULL,
    ADD COLUMN `manual_state` VARCHAR(16) COLLATE utf8mb4_bin NULL,
    ADD COLUMN `manual_observed_at` DATETIME(3) NULL,
    ADD COLUMN `manual_failure_code` VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN `manual_revision` BIGINT NULL,
    ADD COLUMN `manual_hash` CHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN `manual_verified_revision` BIGINT NULL,
    ADD COLUMN `manual_verified_hash` CHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN `manual_verified_lines` JSON NULL,
    ADD COLUMN `manual_verified_at` DATETIME(3) NULL,
    ADD COLUMN `manual_admission_revision` BIGINT NULL,
    ADD COLUMN `manual_admission_state` VARCHAR(24) COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT `ck_bty_manual_observation_domain` CHECK ((`admission_kind` = 'LEGACY_FUNDED' AND `buyer_no` IS NOT NULL AND `customer_kind` IS NULL AND `guest_no` IS NULL AND `identity_revision` IS NULL AND `manual_epoch` IS NULL AND `manual_state` IS NULL AND `manual_observed_at` IS NULL AND `manual_failure_code` IS NULL AND `manual_revision` IS NULL AND `manual_hash` IS NULL AND `manual_verified_revision` IS NULL AND `manual_verified_hash` IS NULL AND `manual_verified_lines` IS NULL AND `manual_verified_at` IS NULL AND `manual_admission_revision` IS NULL AND `manual_admission_state` IS NULL) OR (`admission_kind` = 'MANUAL_OFFLINE' AND (`customer_kind` IS NOT NULL AND ((`customer_kind` = 'USER' AND `buyer_no` IS NOT NULL AND CHAR_LENGTH(`buyer_no`) > 0 AND `guest_no` IS NULL AND (`identity_revision` IS NULL OR `identity_revision` > 0)) OR (`customer_kind` = 'GUEST' AND `buyer_no` IS NULL AND `guest_no` IS NOT NULL AND CHAR_LENGTH(`guest_no`) > 0 AND `identity_revision` IS NOT NULL AND `identity_revision` > 0))) AND `manual_epoch` IS NOT NULL AND `manual_epoch` > 0 AND `manual_state` IS NOT NULL AND `manual_state` IN ('UNKNOWN','READY','BLOCKED') AND `state` = 'UNKNOWN' AND `source_revision` IS NULL AND `source_hash` IS NULL AND `verified_revision` IS NULL AND `verified_hash` IS NULL AND `verified_lines` IS NULL AND `verified_at` IS NULL)),
    ADD CONSTRAINT `ck_bty_manual_observation_pair` CHECK ((`manual_revision` IS NULL AND `manual_hash` IS NULL) OR (`manual_revision` IS NOT NULL AND `manual_revision` > 0 AND `manual_hash` IS NOT NULL AND CHAR_LENGTH(`manual_hash`) = 64)),
    ADD CONSTRAINT `ck_bty_manual_verified_pair` CHECK ((`manual_verified_revision` IS NULL AND `manual_verified_hash` IS NULL AND `manual_verified_lines` IS NULL AND `manual_verified_at` IS NULL) OR (`manual_verified_revision` IS NOT NULL AND `manual_verified_revision` > 0 AND `manual_verified_hash` IS NOT NULL AND CHAR_LENGTH(`manual_verified_hash`) = 64 AND `manual_verified_lines` IS NOT NULL AND JSON_TYPE(`manual_verified_lines`) = 'ARRAY' AND JSON_LENGTH(`manual_verified_lines`) > 0 AND JSON_LENGTH(`manual_verified_lines`) <= 20 AND `manual_verified_at` IS NOT NULL AND `manual_revision` IS NOT NULL AND `manual_verified_revision` <= `manual_revision` AND (`manual_verified_revision` <> `manual_revision` OR `manual_verified_hash` = `manual_hash`))),
    ADD CONSTRAINT `ck_bty_manual_ready` CHECK (`admission_kind` = 'LEGACY_FUNDED' OR (`manual_state` = 'READY' AND `manual_revision` IS NOT NULL AND `manual_verified_revision` IS NOT NULL AND `manual_revision` = `manual_verified_revision` AND `manual_hash` = `manual_verified_hash` AND `manual_observed_at` IS NOT NULL AND `manual_failure_code` IS NULL AND `manual_admission_revision` IS NOT NULL AND `manual_admission_revision` > 0 AND `manual_admission_state` IS NOT NULL AND `manual_admission_state` = 'ADMITTED') OR (`manual_state` IN ('UNKNOWN','BLOCKED') AND `manual_failure_code` IS NOT NULL AND CHAR_LENGTH(`manual_failure_code`) > 0));

ALTER TABLE `bty_trade_binding`
    ADD COLUMN `admission_kind` VARCHAR(24) COLLATE utf8mb4_bin NOT NULL DEFAULT 'LEGACY_FUNDED',
    ADD COLUMN `customer_kind` VARCHAR(8) COLLATE utf8mb4_bin NULL,
    ADD COLUMN `guest_no` VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN `identity_revision` BIGINT NULL,
    ADD COLUMN `buyer_no` VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN `manual_source_revision` BIGINT NULL,
    ADD COLUMN `manual_source_hash` CHAR(64) COLLATE utf8mb4_bin NULL,
    MODIFY COLUMN `source_revision` BIGINT NULL,
    MODIFY COLUMN `source_snapshot_hash` CHAR(64) COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT `ck_bty_trade_binding_manual_domain` CHECK ((`admission_kind` = 'LEGACY_FUNDED' AND `source_revision` IS NOT NULL AND `source_revision` >= 0 AND `source_snapshot_hash` IS NOT NULL AND `manual_source_revision` IS NULL AND `manual_source_hash` IS NULL AND `customer_kind` IS NULL AND `buyer_no` IS NULL AND `guest_no` IS NULL AND `identity_revision` IS NULL) OR (`admission_kind` = 'MANUAL_OFFLINE' AND (`customer_kind` IS NOT NULL AND ((`customer_kind` = 'USER' AND `buyer_no` IS NOT NULL AND CHAR_LENGTH(`buyer_no`) > 0 AND `guest_no` IS NULL AND (`identity_revision` IS NULL OR `identity_revision` > 0)) OR (`customer_kind` = 'GUEST' AND `buyer_no` IS NULL AND `guest_no` IS NOT NULL AND CHAR_LENGTH(`guest_no`) > 0 AND `identity_revision` IS NOT NULL AND `identity_revision` > 0))) AND `source_revision` IS NULL AND `source_snapshot_hash` IS NULL AND `manual_source_revision` IS NOT NULL AND `manual_source_revision` > 0 AND `manual_source_hash` IS NOT NULL AND CHAR_LENGTH(`manual_source_hash`) = 64));

ALTER TABLE `bty_source_allocation_guard`
    ADD COLUMN `admission_kind` VARCHAR(24) COLLATE utf8mb4_bin NOT NULL DEFAULT 'LEGACY_FUNDED',
    ADD COLUMN `customer_kind` VARCHAR(8) COLLATE utf8mb4_bin NULL,
    ADD COLUMN `guest_no` VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN `identity_revision` BIGINT NULL,
    ADD COLUMN `buyer_no` VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN `manual_source_revision` BIGINT NULL,
    ADD COLUMN `manual_source_hash` CHAR(64) COLLATE utf8mb4_bin NULL,
    MODIFY COLUMN `source_revision` BIGINT NULL,
    MODIFY COLUMN `source_hash` CHAR(64) COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT `ck_bty_source_allocation_guard_manual_domain` CHECK ((`admission_kind` = 'LEGACY_FUNDED' AND `source_revision` IS NOT NULL AND `source_revision` >= 0 AND `source_hash` IS NOT NULL AND `manual_source_revision` IS NULL AND `manual_source_hash` IS NULL AND `customer_kind` IS NULL AND `buyer_no` IS NULL AND `guest_no` IS NULL AND `identity_revision` IS NULL) OR (`admission_kind` = 'MANUAL_OFFLINE' AND (`customer_kind` IS NOT NULL AND ((`customer_kind` = 'USER' AND `buyer_no` IS NOT NULL AND CHAR_LENGTH(`buyer_no`) > 0 AND `guest_no` IS NULL AND (`identity_revision` IS NULL OR `identity_revision` > 0)) OR (`customer_kind` = 'GUEST' AND `buyer_no` IS NULL AND `guest_no` IS NOT NULL AND CHAR_LENGTH(`guest_no`) > 0 AND `identity_revision` IS NOT NULL AND `identity_revision` > 0))) AND `source_revision` IS NULL AND `source_hash` IS NULL AND `manual_source_revision` IS NOT NULL AND `manual_source_revision` > 0 AND `manual_source_hash` IS NOT NULL AND CHAR_LENGTH(`manual_source_hash`) = 64));

DELIMITER $$
CREATE TRIGGER `trg_bty_source_observation_domain_guard` BEFORE UPDATE ON `bty_source_observation` FOR EACH ROW
BEGIN
    IF OLD.admission_kind='MANUAL_OFFLINE' AND NOT (NEW.admission_kind='MANUAL_OFFLINE' AND (NEW.`id` <=> OLD.`id`) AND (NEW.`tenant_no` <=> OLD.`tenant_no`) AND (NEW.`entity_no` <=> OLD.`entity_no`) AND (NEW.`store_no` <=> OLD.`store_no`) AND (NEW.`source_kind` <=> OLD.`source_kind`) AND (NEW.`source_no` <=> OLD.`source_no`) AND (NEW.`checkout_caller` <=> OLD.`checkout_caller`) AND (NEW.`checkout_operation_no` <=> OLD.`checkout_operation_no`) AND (NEW.`order_no` <=> OLD.`order_no`) AND (NEW.`buyer_no` <=> OLD.`buyer_no`) AND (NEW.`deleted` <=> OLD.`deleted`) AND (NEW.`created_at` <=> OLD.`created_at`) AND (NEW.`created_by` <=> OLD.`created_by`) AND (NEW.`observation_epoch` <=> OLD.`observation_epoch`) AND (NEW.`state` <=> OLD.`state`) AND (NEW.`source_revision` <=> OLD.`source_revision`) AND (NEW.`source_hash` <=> OLD.`source_hash`) AND (NEW.`verified_revision` <=> OLD.`verified_revision`) AND (NEW.`verified_hash` <=> OLD.`verified_hash`) AND (NEW.`verified_lines` <=> OLD.`verified_lines`) AND (NEW.`verified_at` <=> OLD.`verified_at`) AND (NEW.`observed_at` <=> OLD.`observed_at`) AND (NEW.`failure_code` <=> OLD.`failure_code`) AND (NEW.`customer_kind` <=> OLD.`customer_kind`) AND (NEW.`guest_no` <=> OLD.`guest_no`) AND (NEW.`identity_revision` <=> OLD.`identity_revision`)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable beauty manual source domain';
    END IF;
    IF OLD.admission_kind='LEGACY_FUNDED' AND NEW.admission_kind='MANUAL_OFFLINE' AND NOT (OLD.state='UNKNOWN' AND OLD.`source_revision` IS NULL AND OLD.`source_hash` IS NULL AND OLD.`verified_revision` IS NULL AND OLD.`verified_hash` IS NULL AND OLD.`verified_lines` IS NULL AND OLD.`verified_at` IS NULL AND OLD.`manual_epoch` IS NULL AND OLD.`manual_state` IS NULL AND OLD.`manual_observed_at` IS NULL AND OLD.`manual_failure_code` IS NULL AND OLD.`manual_revision` IS NULL AND OLD.`manual_hash` IS NULL AND OLD.`manual_verified_revision` IS NULL AND OLD.`manual_verified_hash` IS NULL AND OLD.`manual_verified_lines` IS NULL AND OLD.`manual_verified_at` IS NULL AND OLD.`manual_admission_revision` IS NULL AND OLD.`manual_admission_state` IS NULL AND (NEW.`id` <=> OLD.`id`) AND (NEW.`tenant_no` <=> OLD.`tenant_no`) AND (NEW.`entity_no` <=> OLD.`entity_no`) AND (NEW.`store_no` <=> OLD.`store_no`) AND (NEW.`source_kind` <=> OLD.`source_kind`) AND (NEW.`source_no` <=> OLD.`source_no`) AND (NEW.`checkout_caller` <=> OLD.`checkout_caller`) AND (NEW.`checkout_operation_no` <=> OLD.`checkout_operation_no`) AND (NEW.`order_no` <=> OLD.`order_no`) AND (NEW.`buyer_no` <=> OLD.`buyer_no`) AND (NEW.`deleted` <=> OLD.`deleted`) AND (NEW.`created_at` <=> OLD.`created_at`) AND (NEW.`created_by` <=> OLD.`created_by`) AND (NEW.`state` <=> OLD.`state`) AND (NEW.`source_revision` <=> OLD.`source_revision`) AND (NEW.`source_hash` <=> OLD.`source_hash`) AND (NEW.`verified_revision` <=> OLD.`verified_revision`) AND (NEW.`verified_hash` <=> OLD.`verified_hash`) AND (NEW.`verified_lines` <=> OLD.`verified_lines`) AND (NEW.`verified_at` <=> OLD.`verified_at`) AND (NEW.`observed_at` <=> OLD.`observed_at`) AND (NEW.`failure_code` <=> OLD.`failure_code`) AND NEW.observation_epoch=OLD.observation_epoch+1 AND NEW.customer_kind='USER' AND NEW.guest_no IS NULL AND NEW.manual_epoch=1 AND NEW.manual_state='UNKNOWN' AND NOT EXISTS (SELECT 1 FROM bty_trade_binding b WHERE b.tenant_no=OLD.tenant_no AND b.entity_no=OLD.entity_no AND b.source_kind=OLD.source_kind AND b.source_no=OLD.source_no) AND NOT EXISTS (SELECT 1 FROM bty_source_allocation_guard g WHERE g.tenant_no=OLD.tenant_no AND g.entity_no=OLD.entity_no AND g.source_kind=OLD.source_kind AND g.source_no=OLD.source_no)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Ineligible beauty manual source adoption';
    END IF;
END$$
DELIMITER ;
