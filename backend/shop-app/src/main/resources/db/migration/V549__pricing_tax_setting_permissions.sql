-- Management API points only. No role expansion, tax activation, or approval state.
INSERT INTO sys_function_point
    (point_code,function_code,name,group_name,href,ui_perm_code,perm_code,backend_status,ui_ready,
     matrix_code,point_type,sort,created_at,updated_at)
VALUES ('OPS_PRICING_TAX_READ','OPS_SYSTEM','查看默认税配置','结账配置',NULL,
        'pricing:tax:read','pricing:tax:read','IMPLEMENTED',0,'P-4.2','ACTION',91,NOW(),NOW());
INSERT INTO sys_function_point
    (point_code,function_code,name,group_name,href,ui_perm_code,perm_code,backend_status,ui_ready,
     matrix_code,point_type,sort,created_at,updated_at)
VALUES ('OPS_PRICING_TAX_UPDATE','OPS_SYSTEM','维护默认税配置','结账配置',NULL,
        'pricing:tax:update','pricing:tax:update','IMPLEMENTED',0,'P-4.2','ACTION',92,NOW(),NOW());
