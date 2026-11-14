ALTER TABLE ord_ent_service_order ADD COLUMN work_order_claim_snapshot JSON NULL;
ALTER TABLE ord_ent_service_order ADD CONSTRAINT ck_service_work_order_claim CHECK (
    work_order_claim_snapshot IS NULL OR
      (JSON_VALID(work_order_claim_snapshot) AND JSON_TYPE(work_order_claim_snapshot)='OBJECT'));
