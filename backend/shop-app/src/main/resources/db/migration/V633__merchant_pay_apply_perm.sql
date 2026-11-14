-- 为主体申请支付服务的权限码 merchant:merchant:pay-apply（TDD-运营端申请支付服务）。
--
-- 以平台名义在独立支付服务 near-pay 里替商家建平台商户、来源链接与门店关联 —— 开通收款的第一步。
-- 单独一个码，不挂在入驻审核（merchant:apply:audit）或封店（merchant:merchant:ban）下面。
-- 页面内动作，不是菜单：point_type = ACTION、无 href、ui_ready = 0（照 V307 / V632 的写法）。只给超管。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__MERCHANT_MERCHANT_PAY_APPLY', 'OPS_MERCHANT', '申请支付服务', '商家档案', NULL, 'merchant:merchant:pay-apply', 'merchant:merchant:pay-apply', 'IMPLEMENTED', 0, NULL, 'ACTION', 93, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__MERCHANT_MERCHANT_PAY_APPLY');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__MERCHANT_MERCHANT_PAY_APPLY', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__MERCHANT_MERCHANT_PAY_APPLY');
