-- 门店行业维护入口；支付行业/范围/授权码菜单按实际端点读权限展示。
-- 先保留旧菜单授予的 param:read，再迁移菜单；不能借修菜单给技术运维发放行业权限。
INSERT INTO sys_function_point(point_code,function_code,name,group_name,href,ui_perm_code,perm_code,backend_status,ui_ready,matrix_code,point_type,sort,created_at,updated_at)
SELECT 'ACT__SYSTEM_PARAM_READ','OPS_SYSTEM','system:param:read','仅后端',NULL,NULL,'system:param:read','IMPLEMENTED',0,NULL,'ACTION',930,NOW(),NOW()
WHERE NOT EXISTS(SELECT 1 FROM sys_function_point WHERE point_code='ACT__SYSTEM_PARAM_READ');

INSERT IGNORE INTO sys_role_point(role_code,point_code,end_code,created_at,updated_at)
SELECT DISTINCT g.role_code,'ACT__SYSTEM_PARAM_READ',g.end_code,NOW(),NOW()
FROM sys_role_point g JOIN sys_function_point p ON p.point_code=g.point_code
WHERE p.point_code IN ('OPS_SYSTEM__TAB_INDUSTRY','OPS_SYSTEM__TAB_SCOPE','OPS_SYSTEM__TAB_AUTHCODE')
  AND p.perm_code='system:param:read';

-- DISTINCT 物化现有授权，排除正在迁移的三张菜单；保留真正持有目标读权限的角色。
DELETE menu_grant FROM sys_role_point menu_grant
JOIN sys_function_point menu ON menu.point_code=menu_grant.point_code
LEFT JOIN (
  SELECT DISTINCT g.role_code,g.end_code,p.perm_code FROM sys_role_point g
  JOIN sys_function_point p ON p.point_code=g.point_code
  WHERE p.point_code NOT IN ('OPS_SYSTEM__TAB_INDUSTRY','OPS_SYSTEM__TAB_SCOPE','OPS_SYSTEM__TAB_AUTHCODE')
) authority ON authority.role_code=menu_grant.role_code AND authority.end_code=menu_grant.end_code
  AND authority.perm_code=CASE WHEN menu.point_code='OPS_SYSTEM__TAB_AUTHCODE' THEN 'product:category:read' ELSE 'system:industry:read' END
WHERE menu.point_code IN ('OPS_SYSTEM__TAB_INDUSTRY','OPS_SYSTEM__TAB_SCOPE','OPS_SYSTEM__TAB_AUTHCODE')
  AND menu_grant.role_code<>'SUPER_ADMIN' AND authority.role_code IS NULL;

UPDATE sys_function_point SET
  ui_perm_code=CASE WHEN point_code='OPS_SYSTEM__TAB_AUTHCODE' THEN 'product:category:read' ELSE 'system:industry:read' END,
  perm_code=CASE WHEN point_code='OPS_SYSTEM__TAB_AUTHCODE' THEN 'product:category:read' ELSE 'system:industry:read' END,
  updated_at=NOW()
WHERE point_code IN ('OPS_SYSTEM__TAB_INDUSTRY','OPS_SYSTEM__TAB_SCOPE','OPS_SYSTEM__TAB_AUTHCODE');

INSERT IGNORE INTO sys_role_point(role_code,point_code,end_code,created_at,updated_at)
SELECT DISTINCT authority_grant.role_code,menu.point_code,authority_grant.end_code,NOW(),NOW()
FROM sys_role_point authority_grant
JOIN sys_function_point authority ON authority.point_code=authority_grant.point_code
JOIN sys_function_point menu ON menu.perm_code=authority.perm_code
WHERE menu.point_code IN ('OPS_SYSTEM__TAB_INDUSTRY','OPS_SYSTEM__TAB_SCOPE','OPS_SYSTEM__TAB_AUTHCODE')
  AND authority_grant.end_code='OPS';

INSERT INTO sys_function_point(point_code,function_code,name,group_name,href,ui_perm_code,perm_code,backend_status,ui_ready,matrix_code,point_type,sort,created_at,updated_at)
SELECT 'OPS_SYSTEM__TAB_STOREINDUSTRY','OPS_SYSTEM','门店行业与子行业','经营范围','/system?tab=storeIndustry',
  'commerce:catalog:read','commerce:catalog:read','IMPLEMENTED',1,'P-17.1','MENU',95,NOW(),NOW()
WHERE NOT EXISTS(SELECT 1 FROM sys_function_point WHERE point_code='OPS_SYSTEM__TAB_STOREINDUSTRY');

INSERT INTO sys_role_point(role_code,point_code,end_code,created_at,updated_at)
SELECT 'SUPER_ADMIN','OPS_SYSTEM__TAB_STOREINDUSTRY','OPS',NOW(),NOW()
WHERE NOT EXISTS(SELECT 1 FROM sys_role_point WHERE role_code='SUPER_ADMIN' AND point_code='OPS_SYSTEM__TAB_STOREINDUSTRY' AND end_code='OPS');

UPDATE sys_function_point SET gated_by='commerce.catalog.enabled' WHERE point_code='OPS_SYSTEM__TAB_STOREINDUSTRY';
