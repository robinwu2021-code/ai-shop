-- Read-only store-account migration preflight. Run against the Pay schema.
-- Never expose customer details/receipt evidence; counts and amounts only.
SELECT 'accounts' AS metric, COUNT(*) AS rows_count,
       COALESCE(SUM(principal_minor),0) AS principal_minor,
       COALESCE(SUM(bonus_minor),0) AS bonus_minor,
       COALESCE(SUM(held_principal_minor+held_bonus_minor),0) AS held_minor
FROM sv_account WHERE deleted=0;
SELECT 'account_source_stores' AS metric, COALESCE(s.store_count,0) AS source_stores,
       COUNT(*) AS accounts_count
FROM sv_account a LEFT JOIN (
 SELECT l.tenant_no,l.account_no,COUNT(DISTINCT r.store_no) AS store_count
 FROM sv_lot l JOIN sv_recharge r ON r.tenant_no=l.tenant_no AND r.recharge_no=l.recharge_no
 WHERE l.deleted=0 AND r.deleted=0 GROUP BY l.tenant_no,l.account_no
) s ON s.tenant_no=a.tenant_no AND s.account_no=a.account_no
WHERE a.deleted=0 GROUP BY COALESCE(s.store_count,0);
SELECT 'recharge_states' AS metric,status,COUNT(*) AS rows_count,
       COALESCE(SUM(principal_minor+bonus_minor),0) AS promised_minor
FROM sv_recharge WHERE deleted=0 GROUP BY status;
SELECT 'ledger' AS metric,biz_type,COUNT(*) AS vouchers,
       COALESCE(SUM(principal_delta+bonus_delta),0) AS delta_minor
FROM sv_voucher WHERE deleted=0 GROUP BY biz_type;
