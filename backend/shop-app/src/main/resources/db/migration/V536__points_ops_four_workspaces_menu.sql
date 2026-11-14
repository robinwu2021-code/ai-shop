-- Split points operations into its four workspaces and retire the removed store-access entry.
-- Keep the established point codes so existing role grants remain attached to the same capabilities.

UPDATE sys_function
SET name='积分运营', href='/points/types', updated_at=CURRENT_TIMESTAMP
WHERE function_code='OPS_POINTS' AND end_code='OPS' AND deleted=0;

-- Preserve old overview/switch/store-access grants on the remaining settings workspace.
INSERT INTO sys_role_point
  (role_code,point_code,end_code,entity_no,tenant_no,created_at,updated_at)
SELECT DISTINCT source.role_code,'OPS_POINTS__TAB_CONFIGURATION',source.end_code,source.entity_no,source.tenant_no,
       CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM sys_role_point source
WHERE source.end_code='OPS' AND source.deleted=0
  AND source.point_code IN ('OPS_FINANCE__TAB_POINTS','OPS_FINANCE__TAB_POINTS_POLICY','OPS_POINTS__TAB_STORE_ACCESS')
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_point existing
    WHERE existing.role_code=source.role_code AND existing.end_code=source.end_code
      AND existing.tenant_no=source.tenant_no
      AND (existing.entity_no=source.entity_no OR (existing.entity_no IS NULL AND source.entity_no IS NULL))
      AND existing.deleted=0 AND existing.point_code='OPS_POINTS__TAB_CONFIGURATION'
  );

UPDATE sys_function_point
SET function_code='OPS_POINTS', name='积分设置', group_name='积分配置', href='/points/types',
    ui_perm_code='finance:settle:read', perm_code='finance:settle:read', backend_status='IMPLEMENTED',
    ui_ready=1, matrix_code='P-12.1', sort=10, updated_at=CURRENT_TIMESTAMP
WHERE point_code='OPS_POINTS__TAB_CONFIGURATION' AND deleted=0;

UPDATE sys_function_point
SET function_code='OPS_POINTS', name='平台预算', group_name='积分资金', href='/points/budget',
    ui_perm_code='finance:settle:read', perm_code='finance:settle:read', backend_status='IMPLEMENTED',
    ui_ready=1, matrix_code='P-12.1', sort=20, updated_at=CURRENT_TIMESTAMP
WHERE point_code='OPS_POINTS__TAB_TREASURY' AND deleted=0;

UPDATE sys_function_point
SET function_code='OPS_POINTS', name='积分账户', group_name='积分账务', href='/points/accounts',
    ui_perm_code='finance:settle:read', perm_code='finance:settle:read', backend_status='IMPLEMENTED',
    ui_ready=1, matrix_code='P-12.1', sort=30, updated_at=CURRENT_TIMESTAMP
WHERE point_code='OPS_POINTS__TAB_QUERIES' AND deleted=0;

UPDATE sys_function_point
SET function_code='OPS_POINTS', name='发放与核销', group_name='积分账务', href='/points/transactions',
    ui_perm_code='finance:settle:read', perm_code='finance:settle:read', backend_status='IMPLEMENTED',
    ui_ready=1, matrix_code='P-12.1', sort=40, updated_at=CURRENT_TIMESTAMP
WHERE point_code='OPS_POINTS__TAB_SETTLEMENTS' AND deleted=0;

UPDATE sys_role_point
SET deleted=1, updated_at=CURRENT_TIMESTAMP
WHERE end_code='OPS' AND point_code IN (
  'OPS_FINANCE__TAB_POINTS','OPS_FINANCE__TAB_POINTS_POLICY','OPS_POINTS__TAB_STORE_ACCESS'
) AND deleted=0;

UPDATE sys_function_point
SET deleted=1, ui_ready=0, updated_at=CURRENT_TIMESTAMP
WHERE point_code IN (
  'OPS_FINANCE__TAB_POINTS','OPS_FINANCE__TAB_POINTS_POLICY','OPS_POINTS__TAB_STORE_ACCESS'
) AND deleted=0;
