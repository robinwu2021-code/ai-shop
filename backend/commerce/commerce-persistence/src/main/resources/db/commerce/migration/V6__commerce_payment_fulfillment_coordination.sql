-- Existing records are unproven legacy attempts, never inferred never-sent or closed.
ALTER TABLE mall_payment_ref
 MODIFY pay_order_no VARCHAR(64) NULL,
 ADD request_snapshot JSON NULL,
 ADD request_hash VARCHAR(64) NULL,
 ADD outcome_snapshot JSON NULL,
 ADD dispatch_state VARCHAR(24) NOT NULL DEFAULT 'LEGACY_UNKNOWN',
 ADD closure_reference VARCHAR(128) NULL,
 ADD verified_fact_snapshot JSON NULL;
ALTER TABLE mall_fulfillment_task
 ADD order_no VARCHAR(64) NULL,
 ADD request_snapshot JSON NULL,
 ADD request_hash VARCHAR(64) NULL,
 ADD outcome_snapshot JSON NULL,
 ADD dispatch_state VARCHAR(24) NOT NULL DEFAULT 'LEGACY_UNKNOWN',
 ADD INDEX idx_task_order (tenant_no,order_no,id);
