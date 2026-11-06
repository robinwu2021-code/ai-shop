-- 门店信息重推支付系统（near-pay）的权限码 merchant:store:nearpay-sync。
--
-- **为什么单独一个码**：原先借用 merchant:merchant:ban（封店）。重推不改任何数据，
-- 与封店不是一个量级；挂在封店下等于「能封店的人才能重推」，审计也说不清凭什么推。
--
-- 页面内动作，不是菜单：point_type = ACTION、无 href、ui_ready = 0（照 V307 的写法）。
-- 授权：只给超管（运维动作）。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__MERCHANT_STORE_NEARPAY_SYNC', 'OPS_MERCHANT', '门店信息重推支付系统', '门店', NULL, 'merchant:store:nearpay-sync', 'merchant:store:nearpay-sync', 'IMPLEMENTED', 0, NULL, 'ACTION', 92, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__MERCHANT_STORE_NEARPAY_SYNC');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__MERCHANT_STORE_NEARPAY_SYNC', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__MERCHANT_STORE_NEARPAY_SYNC');
