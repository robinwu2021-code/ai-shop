-- Append the real ops page without changing applied seeds or custom role grants.
-- Canonical metadata comes from ops-web nav.ts / point-codes.ts / perm-map.ts.
INSERT INTO sys_function_point(point_code,function_code,name,group_name,href,ui_perm_code,perm_code,backend_status,ui_ready,matrix_code,point_type,gated_by,sort,created_at,updated_at)
VALUES ('OPS_PRINTING','OPS_IAM','打印运营','打印运营','/printing','ops:print:read','ops:print:read','IMPLEMENTED',1,'P-17.1','MENU','shop.print.enabled',50,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE point_code=point_code;
INSERT INTO sys_role_point(role_code,point_code,end_code,created_at,updated_at)
SELECT 'SUPER_ADMIN','OPS_PRINTING','OPS',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM sys_role_point WHERE role_code='SUPER_ADMIN' AND point_code='OPS_PRINTING' AND end_code='OPS');
