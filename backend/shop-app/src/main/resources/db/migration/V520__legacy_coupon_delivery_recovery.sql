-- Technical delivery acknowledgments only; pay remains the owner of units and ledger state.
ALTER TABLE ord_legacy_coupon_checkout
 ADD COLUMN pay_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 ADD COLUMN pay_decision_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
 ADD COLUMN pay_acked_at DATETIME(3) NULL,
 ADD COLUMN recovery_due_at DATETIME(3) NULL,
 ADD KEY ix_legacy_checkout_recovery (tenant_no,pay_state,recovery_due_at,id),
 ADD CONSTRAINT ck_legacy_checkout_pay_ack CHECK (
   (pay_state IN ('PENDING','NONE') AND pay_decision_no IS NULL AND pay_acked_at IS NULL)
   OR (pay_state='COMMITTED' AND status='COMMITTED' AND reversal_snapshot IS NULL AND pay_decision_no IS NOT NULL AND pay_acked_at IS NOT NULL)
   OR (pay_state='RELEASED' AND status='ABORTED' AND pay_decision_no IS NOT NULL AND pay_acked_at IS NOT NULL)
   OR (pay_state='REVERSED' AND status='COMMITTED' AND reversal_snapshot IS NOT NULL AND pay_decision_no IS NOT NULL AND pay_acked_at IS NOT NULL)),
 ADD CONSTRAINT ck_legacy_checkout_pay_queue CHECK (
   (pay_state='PENDING' OR recovery_due_at IS NULL)
   AND (pay_state<>'NONE' OR (status='OPEN' AND held_snapshot IS NULL)));
