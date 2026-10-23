-- Additive source provenance for the legacy Core activity adapter; no wallet/key/money mutation.
ALTER TABLE sv_release ADD COLUMN legacy_activity_version_no VARCHAR(64) NULL;
ALTER TABLE sv_release_tier ADD COLUMN legacy_tier_key VARCHAR(128) NULL;
ALTER TABLE sv_release ADD UNIQUE KEY uk_legacy_activity_version(tenant_no,legacy_activity_version_no);
ALTER TABLE sv_release_tier ADD UNIQUE KEY uk_legacy_activity_tier(tenant_no,release_no,legacy_tier_key);
SET @sv_release_check = (SELECT tc.CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME='sv_release' AND tc.CONSTRAINT_TYPE='CHECK' AND cc.CHECK_CLAUSE LIKE '%source_kind%' LIMIT 1);
SET @sv_release_sql = CONCAT('ALTER TABLE sv_release DROP CHECK `',REPLACE(@sv_release_check,'`','``'),'`');
PREPARE sv_release_statement FROM @sv_release_sql;
EXECUTE sv_release_statement;
DEALLOCATE PREPARE sv_release_statement;
ALTER TABLE sv_release ADD CONSTRAINT ck_sv_release_source CHECK (
 (source_kind='V2_ACTIVITY' AND activity_no IS NOT NULL AND draft_revision_no IS NOT NULL)
 OR (source_kind='LEGACY_PLAN' AND legacy_plan_version_no IS NOT NULL)
 OR (source_kind='LEGACY_ACTIVITY' AND activity_no IS NOT NULL AND legacy_activity_version_no IS NOT NULL AND activity_version_no IS NOT NULL AND activity_version_no=legacy_activity_version_no)
);
SET @sv_release_check = NULL;
SET @sv_release_sql = NULL;
