-- Expose the implemented print diagnostics under platform operations. The runtime switch
-- remains authoritative: /ops/menu marks this item unavailable when print is disabled.
INSERT INTO sys_function_point
  (point_code,function_code,name,group_name,href,ui_perm_code,perm_code,backend_status,ui_ready,matrix_code,gated_by,point_type,sort,created_at,updated_at)
VALUES ('OPS_PRINT','OPS_IAM','打印监控与对账','运行监控','/print','ops:print:read','ops:print:read',
        'IMPLEMENTED',1,'P-17.1','shop.print.enabled','MENU',989,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE function_code='OPS_IAM',name='打印监控与对账',group_name='运行监控',href='/print',
  ui_perm_code='ops:print:read',perm_code='ops:print:read',backend_status='IMPLEMENTED',ui_ready=1,
  matrix_code='P-17.1',gated_by='shop.print.enabled',point_type='MENU',deleted=0,updated_at=CURRENT_TIMESTAMP;

-- Inherit exactly the existing diagnostic read grants, including tenant/entity scope.
INSERT INTO sys_role_point (role_code,point_code,end_code,entity_no,tenant_no,created_at,updated_at)
SELECT DISTINCT source.role_code,'OPS_PRINT',source.end_code,source.entity_no,source.tenant_no,
       CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM sys_role_point source
WHERE source.point_code='ACT__PRINT_READ' AND source.end_code='OPS' AND source.deleted=0
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_point existing
    WHERE existing.role_code=source.role_code AND existing.end_code=source.end_code
      AND existing.tenant_no=source.tenant_no
      AND (existing.entity_no=source.entity_no OR (existing.entity_no IS NULL AND source.entity_no IS NULL))
      AND existing.deleted=0 AND existing.point_code='OPS_PRINT'
  );
