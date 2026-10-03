-- 手动补投影的权限码（inventory:projection:repair，M2）。
--
-- **单独一个码**：它往进销存库里写 INIT 单据。不复用 inventory:stock:read（那是只读），
-- 也不复用 inventory:credential:grant —— 那管的是「谁能读这些货」，是授权；
-- 这一条动的是账本身。
--
-- **只给超管。** 它是修复动作不是日常运营动作：日常状态下 inv-backfill 任务
-- 自己会搬，需要人来点的时候说明链路已经出了别的问题（见「链路健康」那一页）。
--
-- 页面内动作，所以 ACTION 点、无 href、ui_ready = 0（照 V74 那批的写法）。
--
-- ⚠️ 端点默认**试算**：不传 apply 就只返回「会搬多少条」，传了才写。
-- shop.inventory.backfill.dry-run 的默认值（true）是一个决定，
-- 不该被一个按钮悄悄绕过 —— 两步之间隔着一次人的确认。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__INVENTORY_PROJECTION_REPAIR', 'OPS_INVENTORY', '手动补投影', '切换判据', NULL, 'inventory:projection:repair', 'inventory:projection:repair', 'IMPLEMENTED', 0, 'P-18.3', 'ACTION', 36, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__INVENTORY_PROJECTION_REPAIR');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__INVENTORY_PROJECTION_REPAIR', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__INVENTORY_PROJECTION_REPAIR');
