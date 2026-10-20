-- New workspace reuses the established points read/execute permissions.
INSERT INTO sys_function_point
 (point_code,function_code,name,group_name,href,ui_perm_code,perm_code,backend_status,ui_ready,matrix_code,sort,created_at,updated_at)
SELECT 'OPS_POINTS__TAB_EARNING_RULES','OPS_POINTS','赠送规则','积分配置','/points/earning-rules',
       'finance:settle:read','finance:settle:read','IMPLEMENTED',1,'P-12.1',15,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_function_point WHERE point_code='OPS_POINTS__TAB_EARNING_RULES');
INSERT INTO sys_role_point(role_code,point_code,end_code,entity_no,tenant_no,created_at,updated_at)
SELECT source.role_code,'OPS_POINTS__TAB_EARNING_RULES',source.end_code,source.entity_no,source.tenant_no,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM sys_role_point source
WHERE source.point_code='OPS_POINTS__TAB_CONFIGURATION' AND source.end_code='OPS' AND source.deleted=0
 AND NOT EXISTS (SELECT 1 FROM sys_role_point existing WHERE existing.role_code=source.role_code
 AND existing.point_code='OPS_POINTS__TAB_EARNING_RULES' AND existing.end_code=source.end_code
 AND existing.tenant_no=source.tenant_no AND (existing.entity_no=source.entity_no OR (existing.entity_no IS NULL AND source.entity_no IS NULL)) AND existing.deleted=0);
