-- MySQL 9, post-V724, co-located Core and Pay snapshot. Run with a SELECT-only account.
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
SELECT DATABASE() AS snapshot_schema, VERSION() AS mysql_version, UTC_TIMESTAMP(3) AS observed_at_utc;

-- M1. Grain: legacy member and evidence store. A brand member may legitimately span stores.
WITH evidence AS (
    SELECT tenant_no COLLATE utf8mb4_bin AS tenant_no,member_no COLLATE utf8mb4_bin AS member_no,
           entity_no COLLATE utf8mb4_bin AS entity_no,first_store_no COLLATE utf8mb4_bin AS store_no,'FIRST' AS kind
    FROM mbr_member WHERE deleted=0 AND first_store_no IS NOT NULL
    UNION ALL
    SELECT tenant_no, member_no, entity_no, store_no, 'STORE' FROM mbr_member_store WHERE deleted=0
    UNION ALL
    SELECT tenant_no, member_no, entity_no, store_no, 'PROFILE' FROM mbr_store_profile
    WHERE deleted=0 AND member_no IS NOT NULL AND entity_no IS NOT NULL
    UNION ALL
    SELECT tenant_no, member_no, entity_no, store_no, 'SOURCE' FROM mbr_member_source
    WHERE deleted=0 AND store_no IS NOT NULL
), stores AS (
    SELECT tenant_no, member_no, entity_no, store_no, GROUP_CONCAT(DISTINCT kind ORDER BY kind) AS evidence
    FROM evidence GROUP BY tenant_no, member_no, entity_no, store_no
), bindings AS (
    SELECT tenant_no, store_no, SUM(status='PREPARING') AS pending, SUM(status='ACTIVE') AS active_count,
           MAX(CASE WHEN status='ACTIVE' THEN brand_no END) AS brand_no
    FROM mch_brand_store_binding WHERE deleted=0 GROUP BY tenant_no, store_no
), facts AS (
    SELECT m.tenant_no,m.entity_no,m.member_no,m.person_no,s.store_no,s.evidence,
           CASE WHEN st.id IS NOT NULL AND COALESCE(b.pending,0)=0 AND COALESCE(b.active_count,0)<=1
                    THEN IF(b.brand_no IS NULL,'STORE','BRAND') END AS scope_type,
           CASE WHEN st.id IS NOT NULL AND COALESCE(b.pending,0)=0 AND COALESCE(b.active_count,0)<=1
                    THEN COALESCE(b.brand_no,s.store_no) END AS scope_no,
           m.order_count AS entity_orders,m.total_spent_minor AS entity_spent_minor,m.level AS entity_level,
           p.store_profile_no,sm.scope_member_no,l.status AS link_status,m.reach_opt_out,m.status AS legacy_status,
           p.advisor_actor_no IS NOT NULL AS has_store_advisor,p.remark IS NOT NULL AS has_store_remark,
           CASE WHEN (person.user_no IS NOT NULL AND (TRIM(person.user_no)='' OR EXISTS (
                        SELECT 1 FROM usr_person peer WHERE BINARY peer.user_no=BINARY person.user_no
                          AND peer.id<>person.id AND peer.status='ACTIVE' AND peer.deleted=0)))
                     OR COALESCE(b.pending,0)>0 OR b.active_count>1 OR (b.brand_no IS NOT NULL AND brand.id IS NULL)
                     OR (p.id IS NOT NULL AND (p.deleted<>0 OR (p.member_no IS NOT NULL AND BINARY p.member_no<>BINARY m.member_no)))
                     OR EXISTS (SELECT 1 FROM mbr_scope_legacy_link x WHERE BINARY x.tenant_no=BINARY m.tenant_no
                         AND BINARY x.legacy_member_no=BINARY m.member_no AND x.deleted=0 AND x.status='CONFLICT')
                     THEN 'AMBIGUOUS'
                WHEN person.id IS NULL OR st.id IS NULL OR ent.id IS NULL OR (NOT (BINARY s.store_no <=> BINARY m.first_store_no)
                     AND (p.member_no IS NULL OR BINARY p.member_no<>BINARY m.member_no)) THEN 'MISSING'
                ELSE 'UNIQUE' END AS mapping
    FROM mbr_member m
    LEFT JOIN stores s ON BINARY s.tenant_no=BINARY m.tenant_no AND BINARY s.entity_no=BINARY m.entity_no AND BINARY s.member_no=BINARY m.member_no
    LEFT JOIN usr_person person ON BINARY person.tenant_no=BINARY m.tenant_no AND BINARY person.person_no=BINARY m.person_no
        AND person.deleted=0 AND person.status='ACTIVE'
    LEFT JOIN mch_store st ON BINARY st.tenant_no=BINARY m.tenant_no AND BINARY st.store_no=BINARY s.store_no
        AND BINARY st.entity_no=BINARY m.entity_no AND st.deleted=0 AND st.status='ACTIVE'
    LEFT JOIN mch_entity ent ON BINARY ent.tenant_no=BINARY st.tenant_no AND BINARY ent.entity_no=BINARY st.entity_no AND ent.deleted=0 AND ent.status='ACTIVE'
    LEFT JOIN bindings b ON BINARY b.tenant_no=BINARY st.tenant_no AND BINARY b.store_no=BINARY st.store_no
    LEFT JOIN mch_brand brand ON BINARY brand.tenant_no=BINARY b.tenant_no AND BINARY brand.brand_no=BINARY b.brand_no AND brand.status='ACTIVE' AND brand.deleted=0
    LEFT JOIN mbr_store_profile p ON BINARY p.tenant_no=BINARY m.tenant_no AND BINARY p.store_no=BINARY s.store_no
        AND BINARY p.customer_no=BINARY m.person_no
    LEFT JOIN mbr_scope_member sm ON BINARY sm.tenant_no=BINARY m.tenant_no AND BINARY sm.customer_no=BINARY m.person_no
        AND sm.scope_type=IF(b.brand_no IS NULL,'STORE','BRAND') AND BINARY sm.scope_no=COALESCE(BINARY b.brand_no,BINARY s.store_no)
        AND sm.deleted=0
    LEFT JOIN mbr_scope_legacy_link l ON BINARY l.tenant_no=BINARY sm.tenant_no AND BINARY l.scope_member_no=BINARY sm.scope_member_no
        AND BINARY l.legacy_member_no=BINARY m.member_no AND l.deleted=0
    WHERE m.deleted=0
)
SELECT facts.*,COUNT(*) OVER (PARTITION BY tenant_no,mapping) AS rows_in_class FROM facts ORDER BY tenant_no,member_no,store_no;

-- Source records without an owning member cannot enter the join above.
SELECT x.tenant_no,x.source_no,x.member_no,x.entity_no,x.store_no,'MISSING_MEMBER' AS evidence
FROM mbr_member_source x LEFT JOIN mbr_member m ON m.tenant_no=x.tenant_no AND m.member_no=x.member_no AND m.entity_no=x.entity_no AND m.deleted=0
WHERE x.deleted=0 AND m.id IS NULL;

-- Legacy-to-V704 baseline metrics are comparable at the migration watermark only.
SELECT s.tenant_no,s.member_no,s.store_no,s.id AS legacy_id,p.store_profile_no,s.order_count AS old_orders,p.order_count,
       s.total_spent_minor AS old_spent,p.total_spent_minor,s.level AS old_level,p.level,
       CASE WHEN p.id IS NULL THEN 'MISSING_PROFILE'
            WHEN p.deleted<>0 OR NOT (BINARY p.member_no <=> BINARY s.member_no AND BINARY p.store_no <=> BINARY s.store_no
               AND BINARY p.entity_no <=> BINARY s.entity_no AND p.order_count=s.order_count AND p.total_spent_minor=s.total_spent_minor
               AND p.d90_order_count=s.d90_order_count AND p.d90_spent_minor=s.d90_spent_minor AND p.level <=> s.level
               AND p.first_order_at <=> s.first_order_at AND p.last_order_at <=> s.last_order_at AND p.is_first_store=s.is_first_store)
            THEN 'DIFFERENCE' ELSE 'MATCH' END AS metrics
FROM mbr_member_store s LEFT JOIN mbr_store_profile p ON BINARY p.tenant_no=BINARY s.tenant_no AND p.legacy_member_store_id=s.id
WHERE s.deleted=0 ORDER BY s.tenant_no,s.id;

-- A4. Keep every revision, including the overwritten legacy submission's surviving goods.
WITH revisions AS (
    SELECT e.tenant_no,e.enrollment_no,e.activity_no,e.scope_version_no,s.version_no AS revision,
           s.ref_no AS goods_no,s.activity_version_no AS published_version,v.id AS published_id
    FROM pmt_enrollment e JOIN pmt_activity_scope s ON BINARY s.tenant_no=BINARY e.tenant_no
      AND BINARY s.owner_no=BINARY e.enrollment_no AND s.owner_type='ENROLLMENT' AND s.scope_dimension='TARGET'
      AND s.scope_kind='GOODS' AND s.included=1 AND s.deleted=0
    LEFT JOIN pmt_activity_version v ON BINARY v.tenant_no=BINARY s.tenant_no AND BINARY v.version_no=BINARY s.activity_version_no
      AND BINARY v.activity_no=BINARY e.activity_no AND v.deleted=0
    WHERE e.deleted=0
)
SELECT tenant_no,enrollment_no,activity_no,revision COLLATE utf8mb4_bin AS revision,goods_no COLLATE utf8mb4_bin AS goods_no,published_version,
       IF(BINARY revision=BINARY scope_version_no,'CURRENT','PRIOR') AS submission,
       CASE WHEN published_id IS NULL THEN 'UNPROVEN_VERSION'
            WHEN EXISTS (SELECT 1 FROM revisions other WHERE BINARY other.tenant_no=BINARY r.tenant_no AND BINARY other.enrollment_no=BINARY r.enrollment_no
                       AND other.revision=r.revision AND NOT(other.published_version <=> r.published_version)) THEN 'MIXED_VERSION'
            ELSE 'VERSIONED_GOODS' END AS evidence
FROM revisions r
UNION ALL
SELECT e.tenant_no,e.enrollment_no,e.activity_no,e.scope_version_no,g.goods_no,NULL,'CURRENT',
       CASE WHEN e.scope_version_no IS NOT NULL THEN 'BROKEN_REVISION'
            WHEN g.goods_no IS NULL THEN 'MISSING_GOODS' ELSE 'LEGACY_DETAIL' END
FROM pmt_enrollment e LEFT JOIN pmt_enrollment_goods g ON BINARY g.tenant_no=BINARY e.tenant_no AND BINARY g.enrollment_no=BINARY e.enrollment_no AND g.deleted=0
WHERE e.deleted=0 AND (e.scope_version_no IS NULL OR NOT EXISTS (
    SELECT 1 FROM revisions s WHERE BINARY s.tenant_no=BINARY e.tenant_no AND BINARY s.enrollment_no=BINARY e.enrollment_no
      AND BINARY s.revision=BINARY e.scope_version_no))
ORDER BY tenant_no,enrollment_no,revision,goods_no;

SELECT e.tenant_no,e.enrollment_no,e.scope_version_no,g.goods_no,'MISSING_FROM_REVISION' AS mismatch
FROM pmt_enrollment e JOIN pmt_enrollment_goods g ON g.tenant_no=e.tenant_no AND g.enrollment_no=e.enrollment_no AND g.deleted=0
WHERE e.deleted=0 AND e.scope_version_no IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM pmt_activity_scope s WHERE BINARY s.tenant_no=BINARY e.tenant_no AND BINARY s.owner_no=BINARY e.enrollment_no
      AND BINARY s.version_no=BINARY e.scope_version_no AND s.owner_type='ENROLLMENT' AND s.scope_kind='GOODS'
      AND BINARY s.ref_no=BINARY g.goods_no AND s.deleted=0)
UNION ALL
SELECT e.tenant_no,e.enrollment_no,e.scope_version_no,s.ref_no COLLATE utf8mb4_bin,'MISSING_FROM_CURRENT_GOODS'
FROM pmt_enrollment e JOIN pmt_activity_scope s ON BINARY s.tenant_no=BINARY e.tenant_no AND BINARY s.owner_no=BINARY e.enrollment_no
    AND BINARY s.version_no=BINARY e.scope_version_no AND s.owner_type='ENROLLMENT' AND s.scope_kind='GOODS' AND s.deleted=0
WHERE e.deleted=0 AND NOT EXISTS (SELECT 1 FROM pmt_enrollment_goods g WHERE g.tenant_no=e.tenant_no AND g.enrollment_no=e.enrollment_no
    AND BINARY g.goods_no=BINARY s.ref_no AND g.deleted=0);

SELECT r.tenant_no,r.reservation_no,r.reservation_type,r.source_type,r.source_no,r.source_key,r.activity_no,r.version_no,
       r.quota_no,r.quota_units,r.budget_minor,r.currency,r.status,r.deadline,r.pay_fact_no,
       CASE WHEN q.id IS NULL OR BINARY q.activity_no<>BINARY r.activity_no OR q.currency<>r.currency
              OR NOT (BINARY r.source_type <=> BINARY (r.source_snapshot->>'$.sourceType')
                      AND BINARY r.source_no <=> BINARY (r.source_snapshot->>'$.sourceNo')
                      AND r.source_snapshot <=> r.permit_snapshot->'$.sourceRef')
              THEN 'CHECK_BINDING'
            WHEN r.status='RESERVED' AND r.deadline<=UTC_TIMESTAMP(3) THEN 'QUERY_AUTHORITY'
            WHEN r.status='RESERVED' THEN 'HELD'
            WHEN r.status IN ('COMMITTED','RELEASED') AND r.pay_fact_no IS NOT NULL THEN 'TERMINAL'
            ELSE 'CHECK_TERMINAL_FACT' END AS evidence
FROM pmt_reservation r LEFT JOIN pmt_quota q ON BINARY q.tenant_no=BINARY r.tenant_no AND BINARY q.quota_no=BINARY r.quota_no AND q.quota_dimension='TOTAL' AND q.deleted=0
WHERE r.deleted=0 ORDER BY r.tenant_no,r.id;

-- C1. Inspect BOTH roots (including MKT copies) and all issued instances regardless of current status.
WITH roots AS (
    SELECT tenant_no,'PMT' AS src,coupon_no,entity_no,status,funder,archived_at,scope_type,template_version_no,issue_mode,redeem_mode,times_total,min_qty,benefit_ref,received_count,issued_exposure_minor,migration_state,migration_batch_no,deleted
    FROM pmt_coupon
    UNION ALL SELECT tenant_no,'MKT_LEGACY',coupon_no,entity_no,status,funder,archived_at,NULL,NULL,NULL,NULL,NULL,NULL,NULL,received_count,NULL,migration_state,migration_batch_no,deleted FROM mkt_coupon
), issued AS (
    SELECT tenant_no,'PMT' AS src,coupon_no,user_coupon_no,template_version_no,issue_no,used_at FROM pmt_user_coupon
    UNION ALL SELECT tenant_no,'MKT_LEGACY',coupon_no,user_coupon_no,NULL,NULL,used_at FROM mkt_user_coupon
), versions_seen AS (
    SELECT tenant_no,coupon_no,template_version_no COLLATE utf8mb4_bin AS ver FROM pmt_user_coupon
    UNION SELECT tenant_no,coupon_no,template_version_no FROM pmt_coupon_issue
    UNION SELECT tenant_no,coupon_no,template_version_no FROM pmt_coupon
), published AS (
    SELECT pv.tenant_no,pv.entity_no,pv.coupon_no,pv.version_no,pv.rule_snapshot,
           pv.rule_snapshot->>'$.scopeType' AS scope_type,COUNT(s.id) AS scope_rows,
           SUM(CASE WHEN s.id IS NOT NULL AND (s.included<>1 OR s.scope_dimension<>'TARGET'
               OR NOT (s.scope_kind <=> pv.rule_snapshot->>'$.scopeType') OR s.ref_no=''
               OR (s.scope_kind='ALL' AND s.ref_no<>'*')) THEN 1 ELSE 0 END) AS invalid_scope_rows,
           CASE pv.rule_snapshot->>'$.benefitMode'
               WHEN 'CASH' THEN JSON_VALUE(pv.rule_snapshot,'$.benefitValue' RETURNING DECIMAL(30,0) NULL ON ERROR)
               WHEN 'PERCENT' THEN JSON_VALUE(pv.rule_snapshot,'$.benefitCapMinor' RETURNING DECIMAL(30,0) NULL ON ERROR)
           END AS unit_exposure
    FROM pmt_coupon_version pv LEFT JOIN pmt_activity_scope s ON BINARY s.tenant_no=BINARY pv.tenant_no
        AND s.owner_type='ENT_TEMPLATE' AND BINARY s.owner_no=BINARY pv.coupon_no
        AND BINARY s.version_no=BINARY pv.version_no AND s.deleted=0
    WHERE pv.deleted=0 GROUP BY pv.id
), family AS (
    SELECT c.*,
      (SELECT COUNT(*) FROM issued u WHERE BINARY u.tenant_no=BINARY c.tenant_no AND u.src=c.src AND BINARY u.coupon_no=BINARY c.coupon_no) AS instances,
      (SELECT COUNT(*) FROM pmt_coupon_issue i WHERE c.src='PMT' AND BINARY i.tenant_no=BINARY c.tenant_no AND BINARY i.coupon_no=BINARY c.coupon_no) AS batches,
      EXISTS (SELECT 1 FROM roots x WHERE BINARY x.tenant_no=BINARY c.tenant_no AND x.src<>c.src AND BINARY x.coupon_no=BINARY c.coupon_no)
      OR EXISTS (SELECT 1 FROM issued u JOIN issued x ON BINARY x.tenant_no=BINARY u.tenant_no AND x.src<>u.src AND BINARY x.user_coupon_no=BINARY u.user_coupon_no
                 WHERE BINARY u.tenant_no=BINARY c.tenant_no AND u.src=c.src AND BINARY u.coupon_no=BINARY c.coupon_no) AS number_collision,
      COALESCE(c.scope_type='STORE',0) OR EXISTS (SELECT 1 FROM pmt_coupon_scope s WHERE c.src='PMT' AND BINARY s.tenant_no=BINARY c.tenant_no
          AND BINARY s.coupon_no=BINARY c.coupon_no AND s.scope_type='STORE' AND s.deleted=0)
      OR EXISTS (SELECT 1 FROM pmt_activity_scope s WHERE c.src='PMT' AND BINARY s.tenant_no=BINARY c.tenant_no AND s.owner_type='ENT_TEMPLATE'
          AND BINARY s.owner_no=BINARY c.coupon_no AND s.scope_kind='STORE' AND s.deleted=0) AS store_limited,
      (SELECT COUNT(ver) FROM versions_seen v WHERE c.src='PMT' AND BINARY v.tenant_no=BINARY c.tenant_no AND BINARY v.coupon_no=BINARY c.coupon_no) AS versions,
      EXISTS (SELECT 1 FROM versions_seen v LEFT JOIN pmt_coupon_version pv ON BINARY pv.tenant_no=BINARY v.tenant_no AND BINARY pv.coupon_no=BINARY v.coupon_no
          AND BINARY pv.version_no=BINARY v.ver AND pv.deleted=0 WHERE c.src='PMT' AND BINARY v.tenant_no=BINARY c.tenant_no AND BINARY v.coupon_no=BINARY c.coupon_no AND pv.id IS NULL)
      OR EXISTS (SELECT 1 FROM pmt_user_coupon u LEFT JOIN pmt_coupon_issue i ON BINARY i.tenant_no=BINARY u.tenant_no AND BINARY i.coupon_no=BINARY u.coupon_no
          AND BINARY i.issue_no=BINARY u.issue_no AND i.deleted=0 WHERE c.src='PMT' AND BINARY u.tenant_no=BINARY c.tenant_no AND BINARY u.coupon_no=BINARY c.coupon_no
          AND u.issue_no IS NOT NULL AND (i.id IS NULL OR NOT(BINARY i.template_version_no<=>BINARY u.template_version_no))) AS missing_issue_evidence,
      (SELECT COUNT(*) FROM versions_seen v LEFT JOIN published pv ON BINARY pv.tenant_no=BINARY v.tenant_no
          AND BINARY pv.coupon_no=BINARY v.coupon_no AND BINARY pv.version_no=BINARY v.ver AND BINARY pv.entity_no=BINARY c.entity_no
          WHERE c.src='PMT' AND BINARY v.tenant_no=BINARY c.tenant_no AND BINARY v.coupon_no=BINARY c.coupon_no
          AND (pv.version_no IS NULL OR pv.scope_rows=0 OR pv.invalid_scope_rows>0
            OR NOT COALESCE(pv.scope_type IN ('STORE','ALL'),0) OR (pv.scope_type='ALL' AND pv.scope_rows<>1)
            OR NOT COALESCE(pv.unit_exposure>0 AND pv.rule_snapshot->>'$.funder'='MERCHANT'
                AND pv.rule_snapshot->>'$.redeemMode'='ORDER' AND pv.rule_snapshot->>'$.issueMode' IN ('CENTER','TARGETED')
                AND pv.rule_snapshot->>'$.timesTotal'='1',0))) AS incomplete_versions,
      (SELECT SUM(pv.unit_exposure) FROM pmt_user_coupon u JOIN published pv ON BINARY pv.tenant_no=BINARY u.tenant_no
          AND BINARY pv.coupon_no=BINARY u.coupon_no AND BINARY pv.version_no=BINARY u.template_version_no
          AND BINARY pv.entity_no=BINARY c.entity_no WHERE c.src='PMT' AND BINARY u.tenant_no=BINARY c.tenant_no
          AND BINARY u.coupon_no=BINARY c.coupon_no) AS observed_issued_exposure_minor,
      (SELECT COUNT(*) FROM pmt_user_coupon u WHERE c.src='PMT' AND BINARY u.tenant_no=BINARY c.tenant_no
          AND BINARY u.coupon_no=BINARY c.coupon_no AND (u.deleted<>0 OR u.status<>'UNUSED' OR u.times_used<>0
             OR u.used_at IS NOT NULL OR u.order_no IS NOT NULL OR u.received_at<=0 OR u.expire_at<=u.received_at
             OR u.expire_at<=UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 OR NOT (BINARY u.entity_no <=> BINARY c.entity_no))) AS invalid_unused_instances,
      (SELECT COUNT(*) FROM issued u JOIN pmt_apply a ON BINARY a.tenant_no=BINARY u.tenant_no AND a.promo_type='COUPON' AND BINARY a.promo_no=BINARY u.user_coupon_no
          WHERE c.src='PMT' AND BINARY u.tenant_no=BINARY c.tenant_no AND u.src=c.src AND BINARY u.coupon_no=BINARY c.coupon_no) AS applications,
      (SELECT COUNT(*) FROM issued u JOIN pmt_apply a ON BINARY a.tenant_no=BINARY u.tenant_no AND a.promo_type='COUPON' AND BINARY a.promo_no=BINARY u.user_coupon_no
          WHERE c.src='PMT' AND BINARY u.tenant_no=BINARY c.tenant_no AND u.src=c.src AND BINARY u.coupon_no=BINARY c.coupon_no AND a.reverted_at IS NOT NULL) AS original_reverts,
      (SELECT COUNT(*) FROM issued u WHERE BINARY u.tenant_no=BINARY c.tenant_no AND u.src=c.src AND BINARY u.coupon_no=BINARY c.coupon_no AND u.used_at IS NOT NULL) AS used_instances,
      (SELECT COUNT(*) FROM issued u JOIN ent_legacy_coupon_operation op ON BINARY op.tenant_no=BINARY u.tenant_no
          AND op.origin_system=u.src AND op.origin_no=BINARY u.user_coupon_no WHERE BINARY u.tenant_no=BINARY c.tenant_no
          AND u.src=c.src AND BINARY u.coupon_no=BINARY c.coupon_no AND op.status IN ('COMMITTED','REVERSED')) AS routed_uses,
      (SELECT COUNT(*) FROM issued u JOIN ent_legacy_coupon_operation op ON BINARY op.tenant_no=BINARY u.tenant_no
          AND op.origin_system=u.src AND op.origin_no=BINARY u.user_coupon_no WHERE BINARY u.tenant_no=BINARY c.tenant_no
          AND u.src=c.src AND BINARY u.coupon_no=BINARY c.coupon_no AND op.status='REVERSED') AS routed_reversals,
      (SELECT COUNT(*) FROM issued u JOIN pmt_apply a ON BINARY a.tenant_no=BINARY u.tenant_no AND a.promo_type='COUPON'
          AND BINARY a.promo_no=BINARY u.user_coupon_no JOIN ent_migration_return mr ON BINARY mr.tenant_no=BINARY a.tenant_no
          AND mr.source_usage_no=BINARY a.apply_no WHERE c.src='PMT' AND BINARY u.tenant_no=BINARY c.tenant_no
          AND u.src=c.src AND BINARY u.coupon_no=BINARY c.coupon_no) AS historical_return_receipts,
      d.decision_no,i.import_no,o.owner_system,o.status AS owner_status,
      CASE WHEN d.id IS NOT NULL AND i.id IS NOT NULL AND o.owner_system='PAY' AND o.status='CUTOVER'
                 AND BINARY o.migration_batch_no=BINARY d.migration_batch_no AND BINARY o.cutover_watermark=BINARY d.plan_hash AND c.migration_state='COMMIT_DECIDED'
                 AND BINARY c.migration_batch_no=BINARY d.migration_batch_no THEN 'PAY'
           WHEN s.id IS NULL AND c.migration_state IN ('OPEN','ABORT_DECIDED') AND (o.id IS NULL OR o.owner_system='CORE_LEGACY') THEN 'CORE'
           ELSE 'FENCED_OR_INCONSISTENT' END AS writer
    FROM roots c LEFT JOIN pmt_migration_source s ON BINARY s.tenant_no=BINARY c.tenant_no AND BINARY s.source_system=BINARY c.src AND s.source_template_no=BINARY c.coupon_no
    LEFT JOIN pmt_migration_decision d ON BINARY d.tenant_no=BINARY s.tenant_no AND BINARY d.decision_no=BINARY s.decision_no
    LEFT JOIN ent_migration_import i ON BINARY i.tenant_no=BINARY d.tenant_no AND BINARY i.source_decision_no=BINARY d.decision_no AND i.plan_hash=d.plan_hash
    LEFT JOIN ent_owner_registry o ON BINARY o.tenant_no=BINARY c.tenant_no AND BINARY o.source_system=BINARY c.src AND o.source_kind='TEMPLATE' AND o.source_no=BINARY c.coupon_no
)
SELECT family.*,
    SUM(store_limited) OVER (PARTITION BY tenant_no) AS store_limited_roots,
    SUM(versions>1) OVER (PARTITION BY tenant_no) AS multi_version_roots,
    SUM(applications>0 OR used_instances>0 OR routed_uses>0) OVER (PARTITION BY tenant_no) AS used_roots,
    SUM(original_reverts>0 OR routed_reversals>0 OR historical_return_receipts>0) OVER (PARTITION BY tenant_no) AS reverted_roots,
    SUM(number_collision) OVER (PARTITION BY tenant_no) AS colliding_roots,
    SUM(missing_issue_evidence) OVER (PARTITION BY tenant_no) AS unproven_issue_roots,
    CASE WHEN writer='PAY' THEN 'VERIFY_INSTANCE_ROUTES'
    WHEN writer<>'CORE' OR deleted<>0 OR status<>'ACTIVE' OR funder<>'MERCHANT' OR archived_at IS NOT NULL OR NULLIF(entity_no,'') IS NULL THEN 'HOLD'
    WHEN number_collision OR missing_issue_evidence THEN 'HOLD'
    WHEN applications>0 OR used_instances>0 OR routed_uses>0 THEN 'CHECK_ORIGINAL_HISTORY'
    WHEN (versions>1 OR store_limited) AND (src<>'PMT' OR template_version_no IS NULL
        OR scope_type NOT IN ('ALL','STORE') OR redeem_mode<>'ORDER' OR issue_mode NOT IN ('CENTER','TARGETED')
        OR times_total<>1 OR COALESCE(min_qty,0)<>0 OR COALESCE(benefit_ref,'')<>'' OR incomplete_versions>0
        OR invalid_unused_instances>0 OR received_count<>instances OR (versions>1 AND
            NOT (issued_exposure_minor <=> COALESCE(observed_issued_exposure_minor,0)))) THEN 'CHECK_VERSION_EVIDENCE'
    WHEN versions>1 THEN 'RUN_VERSIONED_PREFLIGHT'
    WHEN store_limited AND src='PMT' AND versions=1 AND scope_type='STORE' THEN 'RUN_STORE_PREFLIGHT'
    WHEN store_limited THEN 'HOLD'
    ELSE 'RUN_SOURCE_PREFLIGHT' END AS disposition
FROM family ORDER BY tenant_no,coupon_no,src;

SELECT u.tenant_no,u.coupon_no,u.issue_no,u.user_coupon_no,u.template_version_no AS issued_version,
       i.template_version_no AS batch_version,v.version_no AS snapshot_version,s.scope_kind,s.ref_no,s.included,
       a.import_no,a.archival_only,a.instance_no,o.owner_system,o.status AS instance_owner_status,o.target_no
FROM pmt_user_coupon u
LEFT JOIN pmt_coupon_issue i ON i.tenant_no=u.tenant_no AND i.coupon_no=u.coupon_no AND i.issue_no=u.issue_no
LEFT JOIN pmt_coupon_version v ON BINARY v.tenant_no=BINARY u.tenant_no AND BINARY v.coupon_no=BINARY u.coupon_no
    AND BINARY v.version_no=BINARY u.template_version_no AND v.deleted=0
LEFT JOIN pmt_activity_scope s ON BINARY s.tenant_no=BINARY v.tenant_no AND s.owner_type='ENT_TEMPLATE'
    AND BINARY s.owner_no=BINARY v.coupon_no AND BINARY s.version_no=BINARY v.version_no AND s.scope_dimension='TARGET' AND s.deleted=0
LEFT JOIN ent_migration_asset a ON BINARY a.tenant_no=BINARY u.tenant_no AND a.source_system='PMT' AND a.source_instance_no=BINARY u.user_coupon_no
LEFT JOIN ent_owner_registry o ON BINARY o.tenant_no=BINARY u.tenant_no AND o.source_system='PMT'
    AND o.source_kind='INSTANCE' AND o.source_no=BINARY u.user_coupon_no
ORDER BY u.tenant_no,u.coupon_no,u.user_coupon_no,s.sort_no,s.id;

-- Original apply, imported historical use, Core return decision, Pay receipt and reversal are independently traceable.
SELECT a.tenant_no,u.coupon_no,a.promo_no,a.apply_no,a.order_no,a.reverted_at,m.usage_no,
       d.return_no,d.fact_snapshot->>'$.reason' AS return_reason,d.fact_snapshot->>'$.sourceRefNo' AS return_source_no,
       d.pay_state,p.receipt_no,r.reverse_no,r.source_type,r.source_no
FROM pmt_apply a LEFT JOIN pmt_user_coupon u ON BINARY u.tenant_no=BINARY a.tenant_no AND BINARY u.user_coupon_no=BINARY a.promo_no
LEFT JOIN ent_migration_usage m ON BINARY m.tenant_no=BINARY a.tenant_no AND m.source_usage_no=BINARY a.apply_no
LEFT JOIN pmt_migration_return d ON BINARY d.tenant_no=BINARY a.tenant_no AND d.source_usage_no=BINARY a.apply_no
LEFT JOIN ent_migration_return p ON BINARY p.tenant_no=BINARY d.tenant_no AND BINARY p.return_no=BINARY d.return_no AND BINARY p.source_usage_no=BINARY d.source_usage_no
LEFT JOIN ent_use_reversal r ON BINARY r.tenant_no=BINARY m.tenant_no AND BINARY r.usage_no=BINARY m.usage_no
WHERE a.promo_type='COUPON' ORDER BY a.tenant_no,a.apply_no,r.reverse_no;

-- Orphaned legacy assets have no family on which a source decision can be made.
SELECT u.tenant_no,'PMT' AS src,u.coupon_no,u.user_coupon_no FROM pmt_user_coupon u
LEFT JOIN pmt_coupon c ON c.tenant_no=u.tenant_no AND c.coupon_no=u.coupon_no WHERE c.id IS NULL
UNION ALL
SELECT u.tenant_no,'MKT_LEGACY',u.coupon_no,u.user_coupon_no FROM mkt_user_coupon u
LEFT JOIN mkt_coupon c ON c.tenant_no=u.tenant_no AND c.coupon_no=u.coupon_no WHERE c.id IS NULL;

-- Operations created after cutover keep checkout, source order and reversal source identities.
SELECT op.tenant_no,op.origin_system,op.origin_no,op.instance_no,op.checkout_no,op.operation_no,
       op.status,op.source_order_no,u.usage_no,r.reverse_no,r.source_type,r.source_no,r.after_sale_no
FROM ent_legacy_coupon_operation op LEFT JOIN ent_usage u ON u.tenant_no=op.tenant_no AND u.protocol='LEGACY'
    AND BINARY u.operation_no=BINARY op.operation_no AND BINARY u.legacy_instance_no=BINARY op.instance_no
LEFT JOIN ent_use_reversal r ON r.tenant_no=u.tenant_no AND r.usage_no=u.usage_no
WHERE op.status IN ('HELD','COMMITTED','RELEASED','REVERSED') ORDER BY op.tenant_no,op.operation_no;

COMMIT;
