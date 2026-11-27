-- Only native PMT templates with no definition edit can prove the issue-time rule.
-- PMT save uses optimistic updateById for every edit; V232 copies MKT roots at revision 0.
-- A family is the cutover unit. Preserve fenced roots, every member of a mixed family,
-- and all historical MKT copies. Ownership itself still requires the v2 source decision.
CREATE TEMPORARY TABLE pmt_v710_family (id BIGINT PRIMARY KEY);

START TRANSACTION;
INSERT INTO pmt_v710_family (id)
SELECT c.id FROM pmt_coupon c
WHERE c.tenant_no='MAIN' AND c.funder='MERCHANT' AND c.entity_no IS NOT NULL AND c.entity_no<>''
  AND c.deleted=0 AND c.archived_at IS NULL AND c.status='ACTIVE'
  AND c.migration_state='OPEN' AND c.migration_batch_no IS NULL AND c.version=0
  AND c.template_version_no IS NULL AND c.issued_exposure_minor IS NULL
  AND c.redeem_mode='ORDER' AND c.issue_mode IN ('CENTER','TARGETED') AND c.times_total=1
  AND c.scope_type='ALL' AND COALESCE(c.min_qty,0)=0 AND COALESCE(c.benefit_ref,'')=''
  AND (c.benefit_mode='CASH' AND c.benefit_value>0
    OR c.benefit_mode='PERCENT' AND c.benefit_value BETWEEN 1000 AND 9999 AND c.benefit_cap_minor>0)
  AND (c.validity_mode='ABSOLUTE' AND c.start_at>0 AND c.end_at>c.start_at
    OR c.validity_mode='RELATIVE' AND c.valid_days>0)
  AND c.received_count>0
FOR UPDATE;

-- Every PMT issuer, editor and redeemer locks the root. Read the full family only
-- after its lock was acquired, so concurrent pre-migration writes are visible.
DELETE f FROM pmt_v710_family f JOIN pmt_coupon c ON c.id=f.id
WHERE NOT (c.received_count=(SELECT COUNT(*) FROM pmt_user_coupon u
      WHERE u.tenant_no=c.tenant_no AND u.coupon_no=c.coupon_no)
  AND CAST(c.received_count AS DECIMAL(30))*IF(c.benefit_mode='CASH',c.benefit_value,c.benefit_cap_minor)
        <=COALESCE(NULLIF(c.budget_minor,0),9223372036854775807)
  AND NOT EXISTS (SELECT 1 FROM mkt_coupon m WHERE m.coupon_no=c.coupon_no)
  AND NOT EXISTS (SELECT 1 FROM pmt_coupon_scope s WHERE s.coupon_no=c.coupon_no)
  AND NOT EXISTS (SELECT 1 FROM pmt_user_coupon u WHERE u.coupon_no=c.coupon_no AND (
      u.deleted<>0 OR u.tenant_no<>c.tenant_no OR NOT (u.entity_no <=> c.entity_no)
      OR u.status<>'UNUSED' OR u.times_used<>0 OR u.used_at IS NOT NULL OR u.order_no IS NOT NULL
      OR u.redeem_code IS NOT NULL OR u.template_version_no IS NOT NULL OR u.received_at<=0
      OR u.expire_at<UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000
      OR u.expire_at<>IF(c.validity_mode='ABSOLUTE',c.end_at,u.received_at+CAST(c.valid_days AS SIGNED)*86400000)
      OR EXISTS (SELECT 1 FROM pmt_apply a WHERE a.tenant_no=u.tenant_no AND a.promo_type='COUPON' AND a.promo_no=u.user_coupon_no)
      OR EXISTS (SELECT 1 FROM mkt_user_coupon m WHERE m.user_coupon_no=u.user_coupon_no)
      OR (u.issue_no IS NOT NULL AND NOT EXISTS (SELECT 1 FROM pmt_coupon_issue b
          WHERE b.tenant_no=c.tenant_no AND b.coupon_no=c.coupon_no AND b.issue_no=u.issue_no))))
  AND NOT EXISTS (SELECT 1 FROM pmt_coupon_issue b WHERE b.coupon_no=c.coupon_no AND (
      b.tenant_no<>c.tenant_no OR NOT (b.entity_no <=> c.entity_no) OR b.deleted<>0 OR b.activity_no IS NOT NULL
      OR b.template_version_no IS NOT NULL OR b.issue_mode<>'TARGETED'
      OR b.issued_count<>(SELECT COUNT(*) FROM pmt_user_coupon u
          WHERE u.tenant_no=b.tenant_no AND u.coupon_no=b.coupon_no AND u.issue_no=b.issue_no))));

INSERT INTO pmt_coupon_version (tenant_no,coupon_no,entity_no,version_no,rule_snapshot,created_at,updated_at,created_by,updated_by)
SELECT c.tenant_no,c.coupon_no,c.entity_no,CONCAT('PCV710-',c.id),
    JSON_OBJECT('title',c.title,'funder',c.funder,'benefitMode',c.benefit_mode,'benefitValue',c.benefit_value,
      'benefitCapMinor',c.benefit_cap_minor,'benefitRef',c.benefit_ref,'minAmountMinor',c.min_amount_minor,
      'minQty',c.min_qty,'scopeType',c.scope_type,'scopeDesc',c.scope_desc,'validityMode',c.validity_mode,
      'startAt',c.start_at,'endAt',c.end_at,'validDays',c.valid_days,'issueMode',c.issue_mode,
      'redeemMode',c.redeem_mode,'timesTotal',c.times_total,'totalCount',c.total_count,
      'perUserLimit',c.per_user_limit,'budgetMinor',c.budget_minor),
    UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'MIGRATION_V710','MIGRATION_V710'
FROM pmt_coupon c JOIN pmt_v710_family f ON f.id=c.id;

INSERT INTO pmt_activity_scope (tenant_no,owner_type,owner_no,version_no,scope_dimension,scope_kind,ref_no,
    included,sort_no,created_at,updated_at,created_by,updated_by)
SELECT c.tenant_no,'ENT_TEMPLATE',c.coupon_no,CONCAT('PCV710-',c.id),'TARGET','ALL','*',
    1,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'MIGRATION_V710','MIGRATION_V710'
FROM pmt_coupon c JOIN pmt_v710_family f ON f.id=c.id;

UPDATE pmt_coupon c JOIN pmt_v710_family f ON f.id=c.id
SET c.template_version_no=CONCAT('PCV710-',c.id),
    c.issued_exposure_minor=c.received_count*IF(c.benefit_mode='CASH',c.benefit_value,c.benefit_cap_minor);
UPDATE pmt_user_coupon u JOIN pmt_coupon c ON c.coupon_no=u.coupon_no AND c.tenant_no=u.tenant_no
JOIN pmt_v710_family f ON f.id=c.id SET u.template_version_no=c.template_version_no;
UPDATE pmt_coupon_issue b JOIN pmt_coupon c ON c.coupon_no=b.coupon_no AND c.tenant_no=b.tenant_no
JOIN pmt_v710_family f ON f.id=c.id SET b.template_version_no=c.template_version_no;
COMMIT;

DROP TEMPORARY TABLE pmt_v710_family;
