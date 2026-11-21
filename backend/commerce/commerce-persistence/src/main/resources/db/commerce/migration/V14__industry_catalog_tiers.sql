UPDATE sys_industry_plan
SET `rank` = CASE tier_code WHEN 'CHAIN' THEN 4 ELSE 2 END,
    sort = CASE tier_code WHEN 'CHAIN' THEN 4 ELSE 2 END
WHERE tier_code IN ('PRO', 'CHAIN') AND business_type_code <> 'PAYMENT'
ORDER BY `rank` DESC;

INSERT INTO sys_industry_plan
    (tenant_no, created_at, updated_at, version, deleted, plan_no, business_type_code,
     tier_code, `rank`, sale_status, sort)
SELECT t.tenant_no, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 1, 0,
       CONCAT(t.business_type_code, ':', tiers.tier_code), t.business_type_code,
       tiers.tier_code, tiers.tier_rank, 'DRAFT', tiers.tier_rank
FROM sys_business_type t
CROSS JOIN (
    SELECT 'BASE' AS tier_code, 1 AS tier_rank
    UNION ALL
    SELECT 'GROWTH', 3
) tiers
WHERE t.deleted = 0 AND (t.business_type_code <> 'PAYMENT' OR tiers.tier_code = 'BASE');
