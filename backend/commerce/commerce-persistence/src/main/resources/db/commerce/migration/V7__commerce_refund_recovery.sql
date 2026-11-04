-- Frozen refund intent and verified final result live with the existing refund owner.
ALTER TABLE mall_refund
 ADD COLUMN command_snapshot JSON NULL,
 ADD COLUMN verified_fact_snapshot JSON NULL,
 ADD COLUMN review_evidence VARCHAR(1024) NULL;
