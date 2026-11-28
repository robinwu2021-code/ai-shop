-- 美业在行业库的补充（TDD-美业域迁移新库 B-1）。V1 是设计 DDL 原样（含 A6 的美业执行表，触发器去掉了）；这里补：
-- 一、设计稿去掉的来源观察触发器（美业迁移 V9 原样）；二、执行围栏及其触发器（美业迁移 V10 原样）；
-- 三、过渡：合并完成前代码仍读写的旧表（工单上下文 → B-3；服务参与 → B-4），每完成一步就删掉对应的表（门店策略、策略版本已在 B-2 并入 ind_store_policy、ind_config_revision）。
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

-- 二、执行围栏：表结构取 .61 上美业迁移 V10 执行后的 SHOW CREATE TABLE（服务端规范化的写法；V10 原文经 mysql 客户端执行会被检查约束拒绝），触发器取 V10 原文
CREATE TABLE `bty_service_execution_fence` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN',
  `created_at` datetime(3) NOT NULL,
  `created_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `updated_at` datetime(3) NOT NULL,
  `updated_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '1',
  `deleted` int NOT NULL DEFAULT '0',
  `fence_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `entity_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `store_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `work_order_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `task_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `binding_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `execution_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `submission_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `definition_version_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `resource_command_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `source_no` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `order_no` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `source_operation_no` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `source_kind` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `source_line_no` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `binding_revision` bigint NOT NULL,
  `manual_source_revision` bigint NOT NULL,
  `manual_source_epoch` bigint NOT NULL,
  `initial_task_revision` bigint NOT NULL,
  `scope_revision` bigint NOT NULL,
  `unit_ordinal` int NOT NULL,
  `ordered_units` int NOT NULL,
  `attempt_no` int NOT NULL,
  `quantity` int NOT NULL,
  `uom` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'COUNT',
  `manual_source_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `source_intent_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `binding_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `payload_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `execution_snapshot_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `plan_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `resource_command_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `intent_state` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `source_fence_revision` bigint NOT NULL DEFAULT '1',
  `observation_epoch` bigint NOT NULL DEFAULT '0',
  `next_retry_at` datetime(3) DEFAULT NULL,
  `observed_grant_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `observed_grant_revision` bigint DEFAULT NULL,
  `observed_grant_state` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `suspension_intent_revision` bigint DEFAULT NULL,
  `suspension_manual_epoch` bigint DEFAULT NULL,
  `suspension_observation_version` bigint DEFAULT NULL,
  `suspension_observed_revision` bigint DEFAULT NULL,
  `suspension_observed_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `suspension_reason` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `suspension_proof_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `binding_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bsef_task` (`tenant_no`,`entity_no`,`task_no`),
  UNIQUE KEY `uk_bsef_unit` (`tenant_no`,`source_line_no`,`unit_ordinal`),
  UNIQUE KEY `uk_bsef_execution` (`tenant_no`,`execution_no`),
  UNIQUE KEY `uk_bsef_fence` (`tenant_no`,`fence_no`),
  KEY `idx_bsef_source` (`tenant_no`,`entity_no`,`store_no`,`source_operation_no`,`source_no`,`execution_no`),
  KEY `idx_bsef_recovery` (`tenant_no`,`intent_state`,`next_retry_at`,`id`),
  CONSTRAINT `ck_bsef_base` CHECK (((`tenant_no` = _utf8mb4'MAIN') and (`deleted` = 0) and (`version` >= 0) and (`observation_epoch` >= 0) and (`source_kind` = _utf8mb4'STORE_ORDER'))),
  CONSTRAINT `ck_bsef_binding` CHECK ((json_valid(`binding_json`) and (0 <> coalesce(((json_type(`binding_json`) = _utf8mb4'OBJECT') and (json_type(json_extract(`binding_json`,_utf8mb4'$.schema')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.schema')) = _utf8mb4'MANUAL_EXECUTION_V1') and (json_type(json_extract(`binding_json`,_utf8mb4'$.binding')) = _utf8mb4'OBJECT')),0)))),
  CONSTRAINT `ck_bsef_grant` CHECK ((((`observed_grant_no` is null) and (`observed_grant_revision` is null) and (`observed_grant_state` is null)) or ((`observed_grant_no` is not null) and (`observed_grant_revision` > 0) and (`observed_grant_state` in (_utf8mb4'PREPARED',_utf8mb4'SUSPEND_REQUESTED',_utf8mb4'STARTED',_utf8mb4'STARTED_SUSPENDED',_utf8mb4'RELEASING',_utf8mb4'RELEASED',_utf8mb4'FULFILLED'))))),
  CONSTRAINT `ck_bsef_hashes` CHECK ((regexp_like(`manual_source_hash`,_utf8mb4'^[0-9a-f]{64}$') and regexp_like(`source_intent_hash`,_utf8mb4'^[0-9a-f]{64}$') and regexp_like(`binding_hash`,_utf8mb4'^[0-9a-f]{64}$') and regexp_like(`payload_hash`,_utf8mb4'^[0-9a-f]{64}$') and regexp_like(`execution_snapshot_hash`,_utf8mb4'^[0-9a-f]{64}$') and regexp_like(`plan_hash`,_utf8mb4'^[0-9a-f]{64}$') and regexp_like(`resource_command_hash`,_utf8mb4'^[0-9a-f]{64}$'))),
  CONSTRAINT `ck_bsef_json_attempt_no` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.attemptNo')) = _utf8mb4'INTEGER') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.attemptNo')) = `attempt_no`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_binding_no` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.bindingNo')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.bindingNo')) = `binding_no`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_binding_revision` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.bindingRevision')) = _utf8mb4'INTEGER') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.bindingRevision')) = `binding_revision`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_constants` CHECK ((coalesce(((json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.caller')) = _utf8mb4'BEAUTY') and (json_type(json_extract(`binding_json`,_utf8mb4'$.binding.sourceFenceRevision')) = _utf8mb4'INTEGER') and (json_extract(`binding_json`,_utf8mb4'$.binding.sourceFenceRevision') = 1)),0) = 1)),
  CONSTRAINT `ck_bsef_json_definition_version_no` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.definitionVersionNo')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.definitionVersionNo')) = `definition_version_no`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_entity_no` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.scope.entityNo')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.scope.entityNo')) = `entity_no`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_execution_no` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.executionNo')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.executionNo')) = `execution_no`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_execution_snapshot_hash` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.executionSnapshotHash')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.executionSnapshotHash')) = `execution_snapshot_hash`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_fence_no` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.sourceFenceNo')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.sourceFenceNo')) = `fence_no`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_initial_task_revision` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.taskRevision')) = _utf8mb4'INTEGER') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.taskRevision')) = `initial_task_revision`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_manual_source_epoch` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.sourceEpoch')) = _utf8mb4'INTEGER') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.sourceEpoch')) = `manual_source_epoch`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_manual_source_hash` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.sourceHash')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.sourceHash')) = `manual_source_hash`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_manual_source_revision` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.sourceRevision')) = _utf8mb4'INTEGER') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.sourceRevision')) = `manual_source_revision`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_order_no` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.orderNo')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.orderNo')) = `order_no`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_payload_hash` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.payloadHash')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.payloadHash')) = `payload_hash`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_plan_hash` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.planHash')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.planHash')) = `plan_hash`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_scope_revision` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.scopeRevision')) = _utf8mb4'INTEGER') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.scopeRevision')) = `scope_revision`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_source_line_no` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.sourceLineNo')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.sourceLineNo')) = `source_line_no`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_source_no` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.subOrderNo')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.subOrderNo')) = `source_no`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_source_operation_no` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.sourceOperationNo')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.sourceOperationNo')) = `source_operation_no`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_store_no` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.scope.storeNo')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.scope.storeNo')) = `store_no`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_submission_no` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.submissionNo')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.submissionNo')) = `submission_no`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_task_no` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.taskNo')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.taskNo')) = `task_no`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_tenant_no` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.scope.tenantNo')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.scope.tenantNo')) = `tenant_no`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_unit_ordinal` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.unitOrdinal')) = _utf8mb4'INTEGER') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.unitOrdinal')) = `unit_ordinal`)),0) = 1)),
  CONSTRAINT `ck_bsef_json_work_order_no` CHECK ((coalesce(((json_type(json_extract(`binding_json`,_utf8mb4'$.binding.workOrderNo')) = _utf8mb4'STRING') and (json_unquote(json_extract(`binding_json`,_utf8mb4'$.binding.workOrderNo')) = `work_order_no`)),0) = 1)),
  CONSTRAINT `ck_bsef_revisions` CHECK (((`binding_revision` > 0) and (`manual_source_revision` > 0) and (`manual_source_epoch` > 0) and (`initial_task_revision` >= 0) and (`scope_revision` > 0) and (`source_fence_revision` > 0))),
  CONSTRAINT `ck_bsef_state` CHECK ((`intent_state` in (_utf8mb4'FROZEN',_utf8mb4'EXECUTION_PENDING',_utf8mb4'PREPARED_OBSERVED',_utf8mb4'REJECTED',_utf8mb4'SUSPEND_REQUESTED'))),
  CONSTRAINT `ck_bsef_suspension` CHECK ((((`suspension_intent_revision` is null) and (`suspension_manual_epoch` is null) and (`suspension_observation_version` is null) and (`suspension_observed_revision` is null) and (`suspension_observed_hash` is null) and (`suspension_reason` is null) and (`suspension_proof_hash` is null) and (`intent_state` <> _utf8mb4'SUSPEND_REQUESTED')) or ((`suspension_intent_revision` is not null) and (`suspension_manual_epoch` is not null) and (`suspension_observation_version` is not null) and (`suspension_reason` is not null) and (`suspension_proof_hash` is not null) and (`suspension_intent_revision` > 0) and (`suspension_intent_revision` <= `source_fence_revision`) and (`suspension_manual_epoch` >= 0) and (`suspension_observation_version` >= 0) and ((`suspension_observed_revision` is null) or (`suspension_observed_revision` >= 0)) and ((`suspension_observed_hash` is null) or regexp_like(`suspension_observed_hash`,_utf8mb4'^[0-9a-f]{64}$')) and (`suspension_reason` in (_utf8mb4'SOURCE_SUSPENDED',_utf8mb4'SOURCE_REVISION_CONFLICT',_utf8mb4'SOURCE_IDENTITY_MISMATCH',_utf8mb4'SOURCE_QUANTITY_EXCEEDED',_utf8mb4'TASK_CHANGED',_utf8mb4'PERMANENT_UNIT_CONFLICT')) and regexp_like(`suspension_proof_hash`,_utf8mb4'^[0-9a-f]{64}$') and (`intent_state` = _utf8mb4'SUSPEND_REQUESTED')))),
  CONSTRAINT `ck_bsef_unit` CHECK (((`ordered_units` between 1 and 999) and (`unit_ordinal` between 1 and `ordered_units`) and (`quantity` = 1) and (`uom` = _utf8mb4'COUNT') and (`attempt_no` = 1)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Permanent beauty manual execution source fence';

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

-- 三、过渡表
CREATE TABLE `bty_work_order_context` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '物理自增主键，不对外',
    `tenant_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT '沿当前租户上下文；不另启租户体系',
    `entity_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '商家主体范围；不等于租户',
    `created_at` DATETIME(3) NOT NULL COMMENT '创建时刻',
    `created_by` VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '实际操作者业务引用',
    `updated_at` DATETIME(3) NOT NULL COMMENT '更新时间',
    `updated_by` VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '更新操作者',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
    `deleted` INT NOT NULL DEFAULT 0 COMMENT '沿BaseEntity；事实表禁止删除或软删覆盖',
    `store_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'store_no',
    `work_order_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'work_order_no',
    `identity_kind` VARCHAR(16) COLLATE utf8mb4_bin NOT NULL COMMENT 'identity_kind',
    `identity_ref` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'identity_ref',
    `source_reception_operation_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'source_reception_operation_no',
    `appointment_arrangement_revision` BIGINT NULL COMMENT 'appointment_arrangement_revision',
    `frozen_scope` JSON NOT NULL COMMENT 'frozen_scope',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_bty_work_order_context_1` (`tenant_no`, `entity_no`, `work_order_no`),
    KEY `idx_bty_work_order_context_1` (`tenant_no`, `entity_no`, `store_no`, `identity_kind`, `identity_ref`),
    KEY `idx_bty_work_order_context_2` (`tenant_no`, `entity_no`, `store_no`, `source_reception_operation_no`),
    CONSTRAINT `ck_bty_work_order_context_1` CHECK (`version` >= 0),
    CONSTRAINT `ck_bty_work_order_context_2` CHECK (`deleted` = 0),
    CONSTRAINT `ck_bty_work_order_context_3` CHECK (`identity_kind` IN ('USER','GUEST')),
    CONSTRAINT `ck_bty_work_order_context_4` CHECK (`appointment_arrangement_revision` IS NULL OR `appointment_arrangement_revision` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `bty_service_participation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '物理自增主键，不对外',
    `tenant_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT '沿当前租户上下文；不另启租户体系',
    `entity_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '商家主体范围；不等于租户',
    `created_at` DATETIME(3) NOT NULL COMMENT '创建时刻',
    `created_by` VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '实际操作者业务引用',
    `updated_at` DATETIME(3) NOT NULL COMMENT '更新时间',
    `updated_by` VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '更新操作者',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
    `deleted` INT NOT NULL DEFAULT 0 COMMENT '沿BaseEntity；事实表禁止删除或软删覆盖',
    `participation_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '参与号',
    `task_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '任务',
    `personnel_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '实际人员',
    `assignment_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '有效门店任职',
    `planned_assignment_no` VARCHAR(32) COLLATE utf8mb4_bin NULL COMMENT '原计划安排',
    `role_code` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '技师或助理等业务角色',
    `occupation_no` VARCHAR(32) COLLATE utf8mb4_bin NULL COMMENT '实际资源占用',
    `started_at` DATETIME(3) NOT NULL COMMENT '参与开始',
    `ended_at` DATETIME(3) NULL COMMENT '参与结束',
    `handover_from_no` VARCHAR(32) COLLATE utf8mb4_bin NULL COMMENT '前一参与段',
    `reason` VARCHAR(500) COLLATE utf8mb4_bin NULL COMMENT '交接理由',
    `qualification_snapshot` JSON NOT NULL COMMENT '开工资格与规则版本',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_bty_service_participation_1` (`tenant_no`, `entity_no`, `participation_no`),
    KEY `idx_bty_service_participation_1` (`tenant_no`, `entity_no`, `personnel_no`, `started_at`),
    KEY `idx_bty_service_participation_2` (`tenant_no`, `entity_no`, `task_no`, `started_at`),
    CONSTRAINT `ck_bty_service_participation_1` CHECK (`version` >= 0),
    CONSTRAINT `ck_bty_service_participation_2` CHECK (`deleted` IN (0, 1)),
    CONSTRAINT `ck_bty_service_participation_3` CHECK (`ended_at` IS NULL OR `ended_at` >= `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='实际服务人员参与段';

