-- 两个运营角色在自己菜单内的页面会顺带读别的模块，缺读码时网关拒得对，但页面少一块
-- （v2 页面按角色验证，2026-11-20）：
--   财务     经营财务 / 渠道费率 调 /ops/markets          要 system:param:read
--   商品运营 商品资料 / 商品管理 调 /ops/merchants        要 merchant:merchant:read
-- 只给「仅后端」的动作点，不给菜单点：补读码不能让侧栏多出系统设置、商家档案。
INSERT INTO sys_function_point(point_code,function_code,name,group_name,href,ui_perm_code,perm_code,backend_status,ui_ready,matrix_code,point_type,sort,created_at,updated_at)
SELECT 'ACT__MERCHANT_MERCHANT_READ','OPS_MERCHANT','merchant:merchant:read','仅后端',NULL,NULL,'merchant:merchant:read','IMPLEMENTED',0,NULL,'ACTION',931,NOW(),NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_function_point WHERE point_code='ACT__MERCHANT_MERCHANT_READ');

INSERT INTO sys_role_point(role_code,point_code,end_code,created_at,updated_at) SELECT 'SUPER_ADMIN','ACT__MERCHANT_MERCHANT_READ','OPS',NOW(),NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point WHERE role_code='SUPER_ADMIN' AND point_code='ACT__MERCHANT_MERCHANT_READ' AND end_code='OPS');
INSERT INTO sys_role_point(role_code,point_code,end_code,created_at,updated_at) SELECT 'GOODS_OPS','ACT__MERCHANT_MERCHANT_READ','OPS',NOW(),NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point WHERE role_code='GOODS_OPS' AND point_code='ACT__MERCHANT_MERCHANT_READ' AND end_code='OPS');
INSERT INTO sys_role_point(role_code,point_code,end_code,created_at,updated_at) SELECT 'FINANCE','ACT__SYSTEM_PARAM_READ','OPS',NOW(),NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point WHERE role_code='FINANCE' AND point_code='ACT__SYSTEM_PARAM_READ' AND end_code='OPS');
