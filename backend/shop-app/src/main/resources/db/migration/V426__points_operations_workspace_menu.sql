-- Existing point codes and role grants remain valid when moving the points workspace.
UPDATE sys_function_point SET href='/points?tab=overview',group_name='积分运营',updated_at=CURRENT_TIMESTAMP WHERE point_code='OPS_FINANCE__TAB_POINTS';
UPDATE sys_function_point SET href='/points?tab=switches',name='积分范围开关',group_name='积分运营',updated_at=CURRENT_TIMESTAMP WHERE point_code='OPS_FINANCE__TAB_POINTS_POLICY';

INSERT INTO sys_function_point (point_code,function_code,name,group_name,href,ui_perm_code,perm_code,backend_status,ui_ready,matrix_code,point_type,sort,created_at,updated_at)
SELECT 'OPS_POINTS__TAB_CONFIGURATION','OPS_FINANCE','积分类型与协议','积分运营','/points?tab=configuration','finance:settle:read','finance:settle:read','IMPLEMENTED',1,'P-12.1','MENU',52,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_function_point WHERE point_code='OPS_POINTS__TAB_CONFIGURATION');
INSERT INTO sys_role_point (role_code,point_code,end_code,entity_no,tenant_no,created_at,updated_at)
SELECT DISTINCT source.role_code,'OPS_POINTS__TAB_CONFIGURATION',source.end_code,source.entity_no,source.tenant_no,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM sys_role_point source
WHERE source.point_code='OPS_FINANCE__TAB_POINTS' AND source.end_code='OPS' AND source.deleted=0 AND NOT EXISTS (SELECT 1 FROM sys_role_point existing WHERE existing.role_code=source.role_code AND existing.end_code=source.end_code AND existing.tenant_no=source.tenant_no AND (existing.entity_no=source.entity_no OR (existing.entity_no IS NULL AND source.entity_no IS NULL)) AND existing.deleted=0 AND existing.point_code='OPS_POINTS__TAB_CONFIGURATION');

INSERT INTO sys_function_point (point_code,function_code,name,group_name,href,ui_perm_code,perm_code,backend_status,ui_ready,matrix_code,point_type,sort,created_at,updated_at)
SELECT 'OPS_POINTS__TAB_TREASURY','OPS_FINANCE','积分资金工作台','积分运营','/points?tab=treasury','finance:settle:read','finance:settle:read','IMPLEMENTED',1,'P-12.1','MENU',53,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_function_point WHERE point_code='OPS_POINTS__TAB_TREASURY');
INSERT INTO sys_role_point (role_code,point_code,end_code,entity_no,tenant_no,created_at,updated_at)
SELECT DISTINCT source.role_code,'OPS_POINTS__TAB_TREASURY',source.end_code,source.entity_no,source.tenant_no,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM sys_role_point source
WHERE source.point_code='OPS_FINANCE__TAB_POINTS' AND source.end_code='OPS' AND source.deleted=0 AND NOT EXISTS (SELECT 1 FROM sys_role_point existing WHERE existing.role_code=source.role_code AND existing.end_code=source.end_code AND existing.tenant_no=source.tenant_no AND (existing.entity_no=source.entity_no OR (existing.entity_no IS NULL AND source.entity_no IS NULL)) AND existing.deleted=0 AND existing.point_code='OPS_POINTS__TAB_TREASURY');

INSERT INTO sys_function_point (point_code,function_code,name,group_name,href,ui_perm_code,perm_code,backend_status,ui_ready,matrix_code,point_type,sort,created_at,updated_at)
SELECT 'OPS_POINTS__TAB_SETTLEMENTS','OPS_FINANCE','积分账单与退款','积分运营','/points?tab=settlements','finance:settle:read','finance:settle:read','IMPLEMENTED',1,'P-12.1','MENU',54,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_function_point WHERE point_code='OPS_POINTS__TAB_SETTLEMENTS');
INSERT INTO sys_role_point (role_code,point_code,end_code,entity_no,tenant_no,created_at,updated_at)
SELECT DISTINCT source.role_code,'OPS_POINTS__TAB_SETTLEMENTS',source.end_code,source.entity_no,source.tenant_no,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM sys_role_point source
WHERE source.point_code='OPS_FINANCE__TAB_POINTS' AND source.end_code='OPS' AND source.deleted=0 AND NOT EXISTS (SELECT 1 FROM sys_role_point existing WHERE existing.role_code=source.role_code AND existing.end_code=source.end_code AND existing.tenant_no=source.tenant_no AND (existing.entity_no=source.entity_no OR (existing.entity_no IS NULL AND source.entity_no IS NULL)) AND existing.deleted=0 AND existing.point_code='OPS_POINTS__TAB_SETTLEMENTS');
INSERT INTO sys_function_point (point_code,function_code,name,group_name,href,ui_perm_code,perm_code,backend_status,ui_ready,matrix_code,point_type,sort,created_at,updated_at)
SELECT 'OPS_POINTS__TAB_QUERIES','OPS_FINANCE','积分账务与对账','积分运营','/points?tab=queries','finance:settle:read','finance:settle:read','IMPLEMENTED',1,'P-12.1','MENU',55,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_function_point WHERE point_code='OPS_POINTS__TAB_QUERIES');
INSERT INTO sys_role_point (role_code,point_code,end_code,entity_no,tenant_no,created_at,updated_at)
SELECT DISTINCT source.role_code,'OPS_POINTS__TAB_QUERIES',source.end_code,source.entity_no,source.tenant_no,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM sys_role_point source
WHERE source.point_code='OPS_FINANCE__TAB_POINTS' AND source.end_code='OPS' AND source.deleted=0 AND NOT EXISTS (SELECT 1 FROM sys_role_point existing WHERE existing.role_code=source.role_code AND existing.end_code=source.end_code AND existing.tenant_no=source.tenant_no AND (existing.entity_no=source.entity_no OR (existing.entity_no IS NULL AND source.entity_no IS NULL)) AND existing.deleted=0 AND existing.point_code='OPS_POINTS__TAB_QUERIES');

-- The existing backend reconciliation action now has a concrete points workspace entry.
UPDATE sys_function_point SET ui_perm_code='finance:recon:resolve',ui_ready=1,group_name='积分运营',name='积分对账快照核对',updated_at=CURRENT_TIMESTAMP
WHERE point_code='ACT__FINANCE_RECON_RESOLVE' AND function_code='OPS_FINANCE' AND deleted=0;
