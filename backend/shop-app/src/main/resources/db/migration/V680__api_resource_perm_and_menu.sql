-- 运营端「接口资源」页签的菜单功能点，与停用/恢复链接的独立权限码 system:api-resource:update
-- （TDD-网关层与统一权限 §2.23）。只写功能点与授权，不建表。
--
-- 菜单点挂 system:param:read，授给已持有该码的 SUPER_ADMIN 与 TECH_OPS；
-- 写码走 ACTION 点，只授 SUPER_ADMIN：停用一条链接会让所有人都访问不了它。
-- 写法照 V639：INSERT…SELECT…FROM DUAL…WHERE NOT EXISTS，可重入。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, gated_by, sort, created_at, updated_at)
SELECT 'OPS_SYSTEM__TAB_APIRESOURCE', 'OPS_IAM', '接口资源', '系统维护', '/system?tab=apiResource', 'system:param:read', 'system:param:read', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', NULL, 260, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_SYSTEM__TAB_APIRESOURCE');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_SYSTEM__TAB_APIRESOURCE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_SYSTEM__TAB_APIRESOURCE');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'TECH_OPS', 'OPS_SYSTEM__TAB_APIRESOURCE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='TECH_OPS' AND x.point_code='OPS_SYSTEM__TAB_APIRESOURCE');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, gated_by, sort, created_at, updated_at)
SELECT 'ACT__SYSTEM_API_RESOURCE_UPDATE', 'OPS_IAM', 'system:api-resource:update', '页面内操作', NULL, 'system:api-resource:update', 'system:api-resource:update', 'IMPLEMENTED', 1, NULL, 'ACTION', NULL, 923, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__SYSTEM_API_RESOURCE_UPDATE');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__SYSTEM_API_RESOURCE_UPDATE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__SYSTEM_API_RESOURCE_UPDATE');
