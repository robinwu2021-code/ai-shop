-- Immutable Pay release tiers can serve existing customerNo/null-user recharge flows.
ALTER TABLE sv_recharge_plan ADD COLUMN governed_activity_no VARCHAR(64) NULL;
ALTER TABLE sv_recharge_plan ADD COLUMN governed_tier_key VARCHAR(64) NULL;
ALTER TABLE sv_recharge_plan ADD COLUMN legacy_name_key VARCHAR(100) GENERATED ALWAYS AS (CASE WHEN governed_activity_no IS NULL THEN name ELSE NULL END) STORED;
ALTER TABLE sv_recharge_plan ADD UNIQUE KEY uk_sv_plan_legacy_name(tenant_no,entity_no,store_no,legacy_name_key);
ALTER TABLE sv_recharge_plan ADD UNIQUE KEY uk_sv_plan_governed_tier(tenant_no,governed_activity_no,governed_tier_key);
ALTER TABLE sv_recharge_plan DROP INDEX uk_sv_plan_name;
ALTER TABLE sv_release_tier ADD COLUMN projected_plan_version_no VARCHAR(64) NULL;
ALTER TABLE sv_release_tier ADD UNIQUE KEY uk_sv_tier_projected_plan(tenant_no,projected_plan_version_no);
