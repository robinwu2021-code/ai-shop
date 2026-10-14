-- 方案 v4 P2：运营锁路 + 范围子集的权限点。
-- 锁路用「锁」不用「删」：商家配置原样保留，解锁只能运营，处置结束一键恢复。
ALTER TABLE mch_fulfillment_channel ADD COLUMN ops_locked TINYINT(1) NOT NULL DEFAULT 0 COMMENT '运营锁路：1 = 买家侧不可选、商家侧置灰不可自行改；解锁只能运营（方案 v4 §7.3）';

-- 新权限码 merchant:fulfillment:update（锁路/解锁）。与 V74 同形：仅后端的 ACTION 点，不挂菜单。
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__MERCHANT_FULFILLMENT_UPDATE', 'OPS_MERCHANT', 'merchant:fulfillment:update', '仅后端', NULL, 'merchant:fulfillment:update', 'merchant:fulfillment:update', 'IMPLEMENTED', 0, NULL, 'ACTION', 926, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__MERCHANT_FULFILLMENT_UPDATE');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__MERCHANT_FULFILLMENT_UPDATE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__MERCHANT_FULFILLMENT_UPDATE' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'BD', 'ACT__MERCHANT_FULFILLMENT_UPDATE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='ACT__MERCHANT_FULFILLMENT_UPDATE' AND x.end_code='OPS');
