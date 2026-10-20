-- Give Points Operations its own menu section while keeping existing points point codes and grants.
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at)
VALUES ('OPS_POINTS', '积分运营', 'OPS', 'Coins', '/points', 111, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE name='积分运营', icon='Coins', href='/points', sort=111, enabled=1, deleted=0,
                        updated_at=CURRENT_TIMESTAMP;

-- Move the existing overview/configuration/switches and workspace links out of Finance.
UPDATE sys_function_point SET function_code='OPS_POINTS', name='积分总览', group_name='配置与接入', href='/points',
  updated_at=CURRENT_TIMESTAMP WHERE point_code='OPS_FINANCE__TAB_POINTS' AND deleted=0;
UPDATE sys_function_point SET function_code='OPS_POINTS', name='积分范围开关', group_name='资金与日常（旧版）', href='/points?tab=switches',
  updated_at=CURRENT_TIMESTAMP WHERE point_code='OPS_FINANCE__TAB_POINTS_POLICY' AND deleted=0;
UPDATE sys_function_point SET function_code='OPS_POINTS', name='积分类型与规则', group_name='配置与接入', href='/points/types',
  updated_at=CURRENT_TIMESTAMP WHERE point_code='OPS_POINTS__TAB_CONFIGURATION' AND deleted=0;
UPDATE sys_function_point SET function_code='OPS_POINTS', group_name='资金与日常（旧版）', updated_at=CURRENT_TIMESTAMP
WHERE point_code IN ('OPS_POINTS__TAB_TREASURY','OPS_POINTS__TAB_SETTLEMENTS','OPS_POINTS__TAB_QUERIES') AND deleted=0;

-- The new store route inherits only the read grants already assigned to the points overview.
INSERT INTO sys_function_point
  (point_code,function_code,name,group_name,href,ui_perm_code,perm_code,backend_status,ui_ready,matrix_code,point_type,sort,created_at,updated_at)
SELECT 'OPS_POINTS__TAB_STORE_ACCESS','OPS_POINTS','门店协议与接入','配置与接入','/points/store-access',
       'finance:settle:read','finance:settle:read','IMPLEMENTED',1,'P-12.1','MENU',53,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_function_point WHERE point_code='OPS_POINTS__TAB_STORE_ACCESS');

UPDATE sys_function_point SET function_code='OPS_POINTS', name='门店协议与接入', group_name='配置与接入',
  href='/points/store-access', ui_perm_code='finance:settle:read', perm_code='finance:settle:read',
  backend_status='IMPLEMENTED', ui_ready=1, updated_at=CURRENT_TIMESTAMP
WHERE point_code='OPS_POINTS__TAB_STORE_ACCESS' AND deleted=0;

INSERT INTO sys_role_point (role_code,point_code,end_code,entity_no,tenant_no,created_at,updated_at)
SELECT DISTINCT source.role_code,'OPS_POINTS__TAB_STORE_ACCESS',source.end_code,source.entity_no,source.tenant_no,
       CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM sys_role_point source
WHERE source.point_code='OPS_FINANCE__TAB_POINTS' AND source.end_code='OPS' AND source.deleted=0
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_point existing
    WHERE existing.role_code=source.role_code AND existing.end_code=source.end_code
      AND existing.tenant_no=source.tenant_no
      AND (existing.entity_no=source.entity_no OR (existing.entity_no IS NULL AND source.entity_no IS NULL))
      AND existing.deleted=0 AND existing.point_code='OPS_POINTS__TAB_STORE_ACCESS'
  );
