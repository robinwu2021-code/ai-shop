-- 美业在行业库的补充（TDD-美业域迁移新库 B-1，B-6 同步后只剩触发器）。V1 是设计 DDL 原样：美业的表、执行围栏、库存意向、
-- 指派表的两列与服务步骤状态都已进设计 DDL（06-n_industry.sql）。设计 DDL 不收触发器，这里补：
-- 一、来源观察的领域守卫（美业迁移 V9 原样）；二、执行围栏的两个（V10 原样）；三、库存意向的两个（V11 原样）。
-- 部署 .61 用 deploy/ume/beauty-industry-cutover（它的 03-triggers.sql 与这里同源）。
-- 含 DELIMITER 块：用 mysql 客户端或会处理 DELIMITER 的执行器执行。

-- 一、来源观察的领域守卫触发器
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

-- 二、执行围栏的触发器
DELIMITER $$
CREATE TRIGGER trg_bsef_immutable BEFORE UPDATE ON bty_service_execution_fence
FOR EACH ROW
BEGIN
    IF NOT (BINARY NEW.id <=> BINARY OLD.id) OR
        NOT (BINARY NEW.tenant_no <=> BINARY OLD.tenant_no) OR
        NOT (BINARY NEW.created_at <=> BINARY OLD.created_at) OR
        NOT (BINARY NEW.created_by <=> BINARY OLD.created_by) OR
        NOT (BINARY NEW.fence_no <=> BINARY OLD.fence_no) OR
        NOT (BINARY NEW.entity_no <=> BINARY OLD.entity_no) OR
        NOT (BINARY NEW.store_no <=> BINARY OLD.store_no) OR
        NOT (BINARY NEW.work_order_no <=> BINARY OLD.work_order_no) OR
        NOT (BINARY NEW.task_no <=> BINARY OLD.task_no) OR
        NOT (BINARY NEW.binding_no <=> BINARY OLD.binding_no) OR
        NOT (BINARY NEW.execution_no <=> BINARY OLD.execution_no) OR
        NOT (BINARY NEW.submission_no <=> BINARY OLD.submission_no) OR
        NOT (BINARY NEW.definition_version_no <=> BINARY OLD.definition_version_no) OR
        NOT (BINARY NEW.resource_command_no <=> BINARY OLD.resource_command_no) OR
        NOT (BINARY NEW.source_no <=> BINARY OLD.source_no) OR
        NOT (BINARY NEW.order_no <=> BINARY OLD.order_no) OR
        NOT (BINARY NEW.source_operation_no <=> BINARY OLD.source_operation_no) OR
        NOT (BINARY NEW.source_kind <=> BINARY OLD.source_kind) OR
        NOT (BINARY NEW.source_line_no <=> BINARY OLD.source_line_no) OR
        NOT (BINARY NEW.binding_revision <=> BINARY OLD.binding_revision) OR
        NOT (BINARY NEW.manual_source_revision <=> BINARY OLD.manual_source_revision) OR
        NOT (BINARY NEW.manual_source_epoch <=> BINARY OLD.manual_source_epoch) OR
        NOT (BINARY NEW.initial_task_revision <=> BINARY OLD.initial_task_revision) OR
        NOT (BINARY NEW.scope_revision <=> BINARY OLD.scope_revision) OR
        NOT (BINARY NEW.unit_ordinal <=> BINARY OLD.unit_ordinal) OR
        NOT (BINARY NEW.ordered_units <=> BINARY OLD.ordered_units) OR
        NOT (BINARY NEW.attempt_no <=> BINARY OLD.attempt_no) OR
        NOT (BINARY NEW.quantity <=> BINARY OLD.quantity) OR
        NOT (BINARY NEW.uom <=> BINARY OLD.uom) OR
        NOT (BINARY NEW.manual_source_hash <=> BINARY OLD.manual_source_hash) OR
        NOT (BINARY NEW.source_intent_hash <=> BINARY OLD.source_intent_hash) OR
        NOT (BINARY NEW.binding_hash <=> BINARY OLD.binding_hash) OR
        NOT (BINARY NEW.payload_hash <=> BINARY OLD.payload_hash) OR
        NOT (BINARY NEW.execution_snapshot_hash <=> BINARY OLD.execution_snapshot_hash) OR
        NOT (BINARY NEW.plan_hash <=> BINARY OLD.plan_hash) OR
        NOT (BINARY NEW.resource_command_hash <=> BINARY OLD.resource_command_hash) OR
        NOT (BINARY NEW.binding_json <=> BINARY OLD.binding_json) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Manual execution immutable original';
    END IF;
    IF OLD.suspension_intent_revision IS NOT NULL AND (NOT (BINARY NEW.suspension_intent_revision <=> BINARY OLD.suspension_intent_revision) OR NOT (BINARY NEW.suspension_manual_epoch <=> BINARY OLD.suspension_manual_epoch) OR NOT (BINARY NEW.suspension_observation_version <=> BINARY OLD.suspension_observation_version) OR NOT (BINARY NEW.suspension_observed_revision <=> BINARY OLD.suspension_observed_revision) OR NOT (BINARY NEW.suspension_observed_hash <=> BINARY OLD.suspension_observed_hash) OR NOT (BINARY NEW.suspension_reason <=> BINARY OLD.suspension_reason) OR NOT (BINARY NEW.suspension_proof_hash <=> BINARY OLD.suspension_proof_hash)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Beauty execution suspension is sticky';
    END IF;
END$$

CREATE TRIGGER trg_bsef_no_delete BEFORE DELETE ON bty_service_execution_fence
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Manual execution history cannot be deleted';
END$$
DELIMITER ;

-- 三、库存意向的触发器
DELIMITER $$
CREATE TRIGGER trg_bsesi_immutable BEFORE UPDATE ON bty_service_execution_stock_intent
FOR EACH ROW
BEGIN
    IF NOT (BINARY NEW.id <=> BINARY OLD.id) OR
        NOT (BINARY NEW.tenant_no <=> BINARY OLD.tenant_no) OR
        NOT (BINARY NEW.created_at <=> BINARY OLD.created_at) OR
        NOT (BINARY NEW.created_by <=> BINARY OLD.created_by) OR
        NOT (BINARY NEW.entity_no <=> BINARY OLD.entity_no) OR
        NOT (BINARY NEW.store_no <=> BINARY OLD.store_no) OR
        NOT (BINARY NEW.work_order_no <=> BINARY OLD.work_order_no) OR
        NOT (BINARY NEW.task_no <=> BINARY OLD.task_no) OR
        NOT (BINARY NEW.fence_no <=> BINARY OLD.fence_no) OR
        NOT (BINARY NEW.submission_no <=> BINARY OLD.submission_no) OR
        NOT (BINARY NEW.execution_no <=> BINARY OLD.execution_no) OR
        NOT (BINARY NEW.resource_command_no <=> BINARY OLD.resource_command_no) OR
        NOT (BINARY NEW.attempt_no <=> BINARY OLD.attempt_no) OR
        NOT (BINARY NEW.binding_hash <=> BINARY OLD.binding_hash) OR
        NOT (BINARY NEW.source_intent_hash <=> BINARY OLD.source_intent_hash) OR
        NOT (BINARY NEW.resource_command_hash <=> BINARY OLD.resource_command_hash) OR
        NOT (BINARY NEW.plan_hash <=> BINARY OLD.plan_hash) OR
        NOT (BINARY NEW.reference_hash <=> BINARY OLD.reference_hash) OR
        NOT (BINARY NEW.lookup_hash <=> BINARY OLD.lookup_hash) OR
        NOT (BINARY NEW.retention_no <=> BINARY OLD.retention_no) OR
        NOT (BINARY NEW.request_hash <=> BINARY OLD.request_hash) OR
        NOT (BINARY NEW.reference_json <=> BINARY OLD.reference_json) OR
        NOT (BINARY NEW.lookup_json <=> BINARY OLD.lookup_json) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Manual execution immutable original';
    END IF;
    IF OLD.route_no IS NOT NULL AND (NOT (BINARY NEW.route_no <=> BINARY OLD.route_no) OR NOT (BINARY NEW.route_hash <=> BINARY OLD.route_hash)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Execution stock route is sticky';
    END IF;
    IF NEW.observation_epoch<>OLD.observation_epoch+1 OR NEW.version<>OLD.version+1 OR OLD.observation_epoch>=9223372036854775806 OR OLD.version>=9223372036854775806 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Execution stock observation CAS required';
    END IF;
END$$

CREATE TRIGGER trg_bsesi_no_delete BEFORE DELETE ON bty_service_execution_stock_intent
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Manual execution history cannot be deleted';
END$$
DELIMITER ;
