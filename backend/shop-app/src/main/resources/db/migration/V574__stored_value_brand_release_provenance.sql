-- Expand provenance without rewriting the already-applied P3 constraints or any financial fact.
ALTER TABLE sv_release DROP CHECK ck_sv_release_source;
ALTER TABLE sv_release ADD CONSTRAINT ck_sv_release_source CHECK (
 (source_kind IN ('V2_ACTIVITY','V2_BRAND_ACTIVITY') AND activity_no IS NOT NULL AND draft_revision_no IS NOT NULL)
 OR (source_kind='LEGACY_PLAN' AND legacy_plan_version_no IS NOT NULL)
 OR (source_kind='LEGACY_ACTIVITY' AND activity_no IS NOT NULL AND legacy_activity_version_no IS NOT NULL AND activity_version_no IS NOT NULL AND activity_version_no=legacy_activity_version_no)
);

ALTER TABLE sv_recharge DROP CHECK ck_sv_recharge_source;
ALTER TABLE sv_recharge ADD CONSTRAINT ck_sv_recharge_source CHECK (
 (source_kind='ACTIVITY' AND reservation_no IS NOT NULL AND activity_no IS NOT NULL AND activity_version_no IS NOT NULL AND plan_no IS NULL AND plan_version_no IS NULL)
 OR (source_kind='PLAN' AND reservation_no IS NULL AND activity_no IS NULL AND activity_version_no IS NULL AND plan_no IS NOT NULL AND plan_version_no IS NOT NULL)
 OR (source_kind='ISSUER_RELEASE' AND reservation_no IS NULL AND plan_no IS NULL AND plan_version_no IS NULL AND activity_no IS NOT NULL AND activity_version_no IS NOT NULL
     AND customer_no IS NOT NULL AND issuer_no IS NOT NULL AND issuer_version_no IS NOT NULL AND release_no IS NOT NULL AND tier_no IS NOT NULL AND collection_entity_no IS NOT NULL AND settlement_entity_no IS NOT NULL)
);
