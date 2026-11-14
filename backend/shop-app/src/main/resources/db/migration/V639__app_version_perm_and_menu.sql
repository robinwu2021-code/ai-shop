-- 运营端「App 版本」页签的菜单功能点，与改版本策略的独立权限码 system:app-version:update
-- （TDD-商户App问题回报与版本，已拍板 P5 = B）。
--
-- 为什么另立一个码、不用 system:param:update：抬高「最低支持版本」会让低于它的**所有**旧包
-- 立刻停写（10426），影响面比一般参数大。技术运维持有 param:update，但不该一键让全部商户停写。
-- 这个码一个角色都不配（Perms.ROLE_PERMS 里没有它），只有超管的通配能到；要给发版负责人时，
-- Perms.ROLE_PERMS 加人 + 这里回填 sys_role_point 两步一起做（同 V355 文件头）。
--
-- 菜单点挂 system:param:read（能看策略的人比能改的人多），授给已持有该码的 SUPER_ADMIN 与 TECH_OPS；
-- 写码走 ACTION 点，只授 SUPER_ADMIN。写法照 V355：INSERT…SELECT…FROM DUAL…WHERE NOT EXISTS，可重入。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, gated_by, sort, created_at, updated_at)
SELECT 'OPS_SYSTEM__TAB_APPVERSION', 'OPS_IAM', 'App 版本', '运行配置', '/system?tab=appVersion', 'system:param:read', 'system:param:read', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', NULL, 105, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_SYSTEM__TAB_APPVERSION');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_SYSTEM__TAB_APPVERSION', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_SYSTEM__TAB_APPVERSION');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'TECH_OPS', 'OPS_SYSTEM__TAB_APPVERSION', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='TECH_OPS' AND x.point_code='OPS_SYSTEM__TAB_APPVERSION');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, gated_by, sort, created_at, updated_at)
SELECT 'ACT__SYSTEM_APP_VERSION_UPDATE', 'OPS_IAM', 'system:app-version:update', '页面内操作', NULL, 'system:app-version:update', 'system:app-version:update', 'IMPLEMENTED', 1, NULL, 'ACTION', NULL, 922, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__SYSTEM_APP_VERSION_UPDATE');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__SYSTEM_APP_VERSION_UPDATE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__SYSTEM_APP_VERSION_UPDATE');
