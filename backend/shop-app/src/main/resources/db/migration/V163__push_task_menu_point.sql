-- 营销广播菜单项（/messages?tab=broadcast，设计：触达推送中台 · N6b）。
--
-- 复用 message:template:read/update，不新增权限码：维护触达模板/渠道的与发广播的是同一批运营
-- （同 V92 发送记录的处理）。sort=26，插在 发送记录(20) 与既有通道叶子(21–25) 之后、工单(30) 之前。
-- 角色授权与其它 OPS_MESSAGE 叶子一致：SUPER_ADMIN + SUPPORT。
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MESSAGE__TAB_BROADCAST', 'OPS_MESSAGE', '营销广播', '触达', '/messages?tab=broadcast', 'message:template:read', 'message:template:read', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 26, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MESSAGE__TAB_BROADCAST');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MESSAGE__TAB_BROADCAST', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MESSAGE__TAB_BROADCAST');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPPORT', 'OPS_MESSAGE__TAB_BROADCAST', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='OPS_MESSAGE__TAB_BROADCAST');
