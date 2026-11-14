ALTER TABLE sv_recharge ADD COLUMN source_kind VARCHAR(16) NOT NULL DEFAULT 'ACTIVITY' COMMENT 'ACTIVITY/PLAN';
ALTER TABLE sv_recharge ADD COLUMN plan_no VARCHAR(64) NULL;
ALTER TABLE sv_recharge ADD COLUMN plan_version_no VARCHAR(64) NULL;
ALTER TABLE sv_recharge MODIFY COLUMN reservation_no VARCHAR(64) NULL;
ALTER TABLE sv_recharge MODIFY COLUMN activity_no VARCHAR(64) NULL;
ALTER TABLE sv_recharge MODIFY COLUMN activity_version_no VARCHAR(64) NULL;
ALTER TABLE sv_recharge ADD CONSTRAINT ck_sv_recharge_source CHECK (
 (source_kind='ACTIVITY' AND reservation_no IS NOT NULL AND activity_no IS NOT NULL AND activity_version_no IS NOT NULL AND plan_no IS NULL AND plan_version_no IS NULL)
 OR (source_kind='PLAN' AND reservation_no IS NULL AND activity_no IS NULL AND activity_version_no IS NULL AND plan_no IS NOT NULL AND plan_version_no IS NOT NULL));
ALTER TABLE sv_lot MODIFY COLUMN activity_version_no VARCHAR(64) NULL;
ALTER TABLE sv_lot ADD COLUMN plan_version_no VARCHAR(64) NULL;
ALTER TABLE sv_lot ADD COLUMN recharge_store_no VARCHAR(64) NULL;
