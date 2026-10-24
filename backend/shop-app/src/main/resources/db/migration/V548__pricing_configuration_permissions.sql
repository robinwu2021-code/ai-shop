-- API-only pricing grants. No role grants, menu visibility, tax approval, or store migration.
INSERT INTO sys_function_point
    (point_code,function_code,name,group_name,href,ui_perm_code,perm_code,backend_status,ui_ready,
     matrix_code,point_type,sort,created_at,updated_at)
VALUES ('OPS_PRICING_TEMPLATE_READ','OPS_SYSTEM','查看结账模板','结账配置',NULL,
        'pricing:template:read','pricing:template:read','IMPLEMENTED',0,'P-4.2','ACTION',90,NOW(),NOW());
INSERT INTO sys_function_point
    (point_code,function_code,name,group_name,href,ui_perm_code,perm_code,backend_status,ui_ready,
     matrix_code,point_type,sort,created_at,updated_at)
VALUES ('OPS_PRICING_TEMPLATE_UPDATE','OPS_SYSTEM','维护结账模板草稿','结账配置',NULL,
        'pricing:template:update','pricing:template:update','IMPLEMENTED',0,'P-4.2','ACTION',90,NOW(),NOW());
INSERT INTO sys_function_point
    (point_code,function_code,name,group_name,href,ui_perm_code,perm_code,backend_status,ui_ready,
     matrix_code,point_type,sort,created_at,updated_at)
VALUES ('OPS_PRICING_TEMPLATE_PUBLISH','OPS_SYSTEM','发布结账模板','结账配置',NULL,
        'pricing:template:publish','pricing:template:publish','IMPLEMENTED',0,'P-4.2','ACTION',90,NOW(),NOW());
