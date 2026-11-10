-- Actual workflow uses the existing submission journal. Historical originals are not rewritten.
ALTER TABLE bty_service_submission
    ADD COLUMN workflow_observation_json JSON NULL,
    ADD CONSTRAINT ck_bty_task_flow CHECK (
        (action<>'TASK_FLOW' AND workflow_observation_json IS NULL) OR
        (action='TASK_FLOW' AND execution_no IS NULL AND action_observation_json IS NULL
         AND deleted=0 AND status IN ('INTENDED','RESOURCE_APPLIED','APPLIED','CANCEL_REQUESTED','CANCELLED') AND workflow_observation_json IS NOT NULL
         AND COALESCE(JSON_TYPE(frozen_command)='OBJECT'
          AND JSON_UNQUOTE(JSON_EXTRACT(frozen_command,'$.schema'))='BEAUTY_TASK_WORKFLOW_V1'
          AND JSON_UNQUOTE(JSON_EXTRACT(frozen_command,'$.operationNo'))=submission_no
          AND JSON_UNQUOTE(JSON_EXTRACT(frozen_command,'$.frozenHash'))=payload_hash
          AND JSON_UNQUOTE(JSON_EXTRACT(frozen_command,'$.keyHash'))=request_key_hash
          AND JSON_UNQUOTE(JSON_EXTRACT(frozen_command,'$.start.action.context.scope.tenantNo'))=tenant_no
          AND JSON_UNQUOTE(JSON_EXTRACT(frozen_command,'$.start.action.context.scope.entityNo'))=entity_no
          AND JSON_UNQUOTE(JSON_EXTRACT(frozen_command,'$.start.action.context.scope.storeNo'))=store_no
          AND JSON_UNQUOTE(JSON_EXTRACT(frozen_command,'$.start.action.context.workOrderNo'))=work_order_no
          AND JSON_UNQUOTE(JSON_EXTRACT(frozen_command,'$.request.action')) IN ('PAUSE','RESUME','HANDOVER','END','CLEAN')
          AND JSON_TYPE(JSON_EXTRACT(workflow_observation_json,'$.fulfilled'))='BOOLEAN'
          AND JSON_TYPE(JSON_EXTRACT(workflow_observation_json,'$.humanCommitted'))='BOOLEAN'
          AND JSON_TYPE(JSON_EXTRACT(workflow_observation_json,'$.cancellationRequested'))='BOOLEAN'
          AND ((status='INTENDED' AND JSON_TYPE(JSON_EXTRACT(workflow_observation_json,'$.resource'))='NULL')
            OR (status IN ('RESOURCE_APPLIED','APPLIED') AND JSON_TYPE(JSON_EXTRACT(workflow_observation_json,'$.resource'))='OBJECT')
            OR status='CANCEL_REQUESTED'
            OR (status='CANCELLED' AND JSON_TYPE(JSON_EXTRACT(workflow_observation_json,'$.resource'))='NULL'
                AND JSON_UNQUOTE(JSON_EXTRACT(workflow_observation_json,'$.cancellation.outcome'))='NO_EFFECT_SEALED')),0)));

DELIMITER $$
CREATE TRIGGER trg_bty_task_flow_immutable BEFORE UPDATE ON bty_service_submission
FOR EACH ROW
BEGIN
    IF OLD.action IN ('TASK_FLOW','HANDOVER_PROPOSAL','TASK_START_COMMIT') THEN
        IF NOT (BINARY NEW.frozen_command <=> BINARY OLD.frozen_command)
          OR NOT (BINARY NEW.payload_hash <=> BINARY OLD.payload_hash)
          OR NOT (BINARY NEW.request_key_hash <=> BINARY OLD.request_key_hash)
          OR NOT (BINARY NEW.tenant_no <=> BINARY OLD.tenant_no)
          OR NOT (BINARY NEW.entity_no <=> BINARY OLD.entity_no)
          OR NOT (BINARY NEW.store_no <=> BINARY OLD.store_no)
          OR NOT (BINARY NEW.work_order_no <=> BINARY OLD.work_order_no)
          OR NOT (BINARY NEW.submission_no <=> BINARY OLD.submission_no)
          OR NOT (BINARY NEW.action <=> BINARY OLD.action)
          OR NOT (NEW.created_at <=> OLD.created_at) OR NOT (NEW.created_by <=> OLD.created_by)
          OR NOT (NEW.deleted <=> OLD.deleted) OR NEW.version<>OLD.version+1
          OR OLD.action IN ('HANDOVER_PROPOSAL','TASK_START_COMMIT')
          OR OLD.status='CANCELLED'
          OR (OLD.action='TASK_FLOW' AND JSON_TYPE(JSON_EXTRACT(OLD.workflow_observation_json,'$.resource'))='OBJECT'
              AND NOT (JSON_EXTRACT(NEW.workflow_observation_json,'$.resource') <=> JSON_EXTRACT(OLD.workflow_observation_json,'$.resource')))
          OR (OLD.status='APPLIED' AND (NEW.status<>'APPLIED'
              OR NOT (JSON_EXTRACT(NEW.workflow_observation_json,'$.resource') <=> JSON_EXTRACT(OLD.workflow_observation_json,'$.resource'))
              OR (JSON_EXTRACT(OLD.workflow_observation_json,'$.fulfilled')=true
                  AND JSON_EXTRACT(NEW.workflow_observation_json,'$.fulfilled')<>true))) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='immutable actual workflow original';
        END IF;
    END IF;
END$$
CREATE TRIGGER trg_bty_task_flow_no_delete BEFORE DELETE ON bty_service_submission
FOR EACH ROW
BEGIN
    IF OLD.action IN ('TASK_FLOW','HANDOVER_PROPOSAL','TASK_START_COMMIT') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='permanent actual workflow original';
    END IF;
END$$
DELIMITER ;
