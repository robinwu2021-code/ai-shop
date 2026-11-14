-- Member authorization and Pay usage remain external owner facts. This journal records local allocation.
ALTER TABLE bty_service_submission
    ADD COLUMN entitlement_task_no VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN entitlement_operation_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN entitlement_active_task_no VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD COLUMN entitlement_usage_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN entitlement_receipt_json JSON NULL,
    ADD UNIQUE KEY uk_bty_entitlement_operation (tenant_no,entity_no,entitlement_operation_no),
    ADD UNIQUE KEY uk_bty_entitlement_active_task (tenant_no,entity_no,entitlement_active_task_no),
    ADD UNIQUE KEY uk_bty_entitlement_usage (tenant_no,entitlement_usage_no),
    ADD CONSTRAINT ck_bty_entitlement_journal CHECK (
      (action<>'ENTITLEMENT_USE' AND entitlement_task_no IS NULL AND entitlement_operation_no IS NULL
        AND entitlement_active_task_no IS NULL AND entitlement_usage_no IS NULL AND entitlement_receipt_json IS NULL)
      OR (action='ENTITLEMENT_USE' AND work_order_no IS NOT NULL AND entitlement_task_no IS NOT NULL
        AND entitlement_operation_no IS NOT NULL AND deleted=0 AND next_retry_at IS NULL
        AND ((status='RESERVED' AND entitlement_active_task_no IS NOT NULL AND entitlement_active_task_no=entitlement_task_no
              AND entitlement_usage_no IS NULL AND entitlement_receipt_json IS NULL)
          OR (status='BOUND' AND entitlement_active_task_no IS NOT NULL AND entitlement_active_task_no=entitlement_task_no
              AND entitlement_usage_no IS NOT NULL AND entitlement_receipt_json IS NOT NULL)
          OR (status='RELEASED' AND entitlement_active_task_no IS NULL
              AND entitlement_usage_no IS NULL AND entitlement_receipt_json IS NULL))));

ALTER TABLE bty_trade_binding DROP CHECK ck_bty_trade_binding_manual_domain;
ALTER TABLE bty_trade_binding ADD CONSTRAINT ck_bty_trade_binding_manual_domain CHECK ((`admission_kind` = 'LEGACY_FUNDED' AND `source_revision` IS NOT NULL AND `source_revision` >= 0 AND `source_snapshot_hash` IS NOT NULL AND `manual_source_revision` IS NULL AND `manual_source_hash` IS NULL AND `customer_kind` IS NULL AND `buyer_no` IS NULL AND `guest_no` IS NULL AND `identity_revision` IS NULL) OR (`admission_kind` = 'MANUAL_OFFLINE' AND (`customer_kind` IS NOT NULL AND ((`customer_kind` = 'USER' AND `buyer_no` IS NOT NULL AND CHAR_LENGTH(`buyer_no`) > 0 AND `guest_no` IS NULL AND (`identity_revision` IS NULL OR `identity_revision` > 0)) OR (`customer_kind` = 'GUEST' AND `buyer_no` IS NULL AND `guest_no` IS NOT NULL AND CHAR_LENGTH(`guest_no`) > 0 AND `identity_revision` IS NOT NULL AND `identity_revision` > 0))) AND `source_revision` IS NULL AND `source_snapshot_hash` IS NULL AND `manual_source_revision` IS NOT NULL AND `manual_source_revision` > 0 AND `manual_source_hash` IS NOT NULL AND CHAR_LENGTH(`manual_source_hash`) = 64) OR (`admission_kind`='ENTITLEMENT_USE' AND `source_kind`='SERVICE_USE' AND `source_revision` IS NOT NULL AND `source_revision`>=0 AND `source_snapshot_hash` IS NOT NULL AND `manual_source_revision` IS NULL AND `manual_source_hash` IS NULL AND `customer_kind` IS NULL AND `buyer_no` IS NULL AND `guest_no` IS NULL AND `identity_revision` IS NULL));

ALTER TABLE bty_source_allocation_guard DROP CHECK ck_bty_source_allocation_guard_manual_domain;
ALTER TABLE bty_source_allocation_guard ADD CONSTRAINT ck_bty_source_allocation_guard_manual_domain CHECK ((`admission_kind` = 'LEGACY_FUNDED' AND `source_revision` IS NOT NULL AND `source_revision` >= 0 AND `source_hash` IS NOT NULL AND `manual_source_revision` IS NULL AND `manual_source_hash` IS NULL AND `customer_kind` IS NULL AND `buyer_no` IS NULL AND `guest_no` IS NULL AND `identity_revision` IS NULL) OR (`admission_kind` = 'MANUAL_OFFLINE' AND (`customer_kind` IS NOT NULL AND ((`customer_kind` = 'USER' AND `buyer_no` IS NOT NULL AND CHAR_LENGTH(`buyer_no`) > 0 AND `guest_no` IS NULL AND (`identity_revision` IS NULL OR `identity_revision` > 0)) OR (`customer_kind` = 'GUEST' AND `buyer_no` IS NULL AND `guest_no` IS NOT NULL AND CHAR_LENGTH(`guest_no`) > 0 AND `identity_revision` IS NOT NULL AND `identity_revision` > 0))) AND `source_revision` IS NULL AND `source_hash` IS NULL AND `manual_source_revision` IS NOT NULL AND `manual_source_revision` > 0 AND `manual_source_hash` IS NOT NULL AND CHAR_LENGTH(`manual_source_hash`) = 64) OR (`admission_kind`='ENTITLEMENT_USE' AND `source_kind`='SERVICE_USE' AND `source_revision` IS NOT NULL AND `source_revision`>=0 AND `source_hash` IS NOT NULL AND `manual_source_revision` IS NULL AND `manual_source_hash` IS NULL AND `customer_kind` IS NULL AND `buyer_no` IS NULL AND `guest_no` IS NULL AND `identity_revision` IS NULL));

DELIMITER $$
CREATE TRIGGER trg_bty_entitlement_original BEFORE UPDATE ON bty_service_submission FOR EACH ROW
BEGIN
    IF OLD.action='ENTITLEMENT_USE' THEN
        IF NOT (NEW.id <=> OLD.id) OR
       NOT (NEW.tenant_no <=> OLD.tenant_no) OR
       NOT (NEW.entity_no <=> OLD.entity_no) OR
       NOT (NEW.store_no <=> OLD.store_no) OR
       NOT (NEW.work_order_no <=> OLD.work_order_no) OR
       NOT (NEW.submission_no <=> OLD.submission_no) OR
       NOT (NEW.action <=> OLD.action) OR
       NOT (NEW.created_at <=> OLD.created_at) OR
       NOT (NEW.created_by <=> OLD.created_by) OR
       NOT (NEW.request_key_hash <=> OLD.request_key_hash) OR
       NOT (NEW.payload_hash <=> OLD.payload_hash) OR
       NOT (NEW.frozen_command <=> OLD.frozen_command) OR
       NOT (NEW.entitlement_task_no <=> OLD.entitlement_task_no) OR
       NOT (NEW.entitlement_operation_no <=> OLD.entitlement_operation_no) OR
       NOT (NEW.deleted <=> OLD.deleted) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Entitlement task original is immutable';
        END IF;
        IF NOT ((OLD.status='RESERVED' AND NEW.status IN ('RESERVED','BOUND','RELEASED'))
                OR OLD.status=NEW.status) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Entitlement task transition is terminal';
        END IF;
        IF OLD.status IN ('BOUND','RELEASED') AND
          (NOT (NEW.entitlement_usage_no <=> OLD.entitlement_usage_no)
           OR NOT (NEW.entitlement_receipt_json <=> OLD.entitlement_receipt_json)
           OR NOT (NEW.entitlement_active_task_no <=> OLD.entitlement_active_task_no)) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Entitlement task receipt is permanent';
        END IF;
    END IF;
END$$
CREATE TRIGGER trg_bty_entitlement_no_delete BEFORE DELETE ON bty_service_submission FOR EACH ROW
BEGIN
    IF OLD.action='ENTITLEMENT_USE' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Entitlement task original cannot be deleted';
    END IF;
END$$
DELIMITER ;
