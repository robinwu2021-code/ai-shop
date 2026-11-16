-- Explicit store operating admission; NULL retains unknown historic review provenance.
-- No change to ordinary FREE creation, entity qualification or payment onboarding.
ALTER TABLE mch_store
 ADD COLUMN operational_review VARCHAR(16) NULL COMMENT 'NULL historic/unsubmitted, PENDING/APPROVED/REJECTED',
 ADD COLUMN operational_source VARCHAR(16) NULL COMMENT 'MANUAL/STORE/ENTITY',
 ADD COLUMN operational_order_no VARCHAR(64) NULL COMMENT 'Frozen fulfillment order, only for paid approval',
 ADD COLUMN operational_submitted_at BIGINT NULL COMMENT 'Admission submission epoch milliseconds',
 ADD COLUMN operational_decided_at BIGINT NULL COMMENT 'Operating admission decision epoch milliseconds';
