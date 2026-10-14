-- 发送记录菜单项（/messages?tab=notifyLog）。
--
-- **与页面同批落地**：V90 里本来带着这一条，被守卫（nav-function-point）拦下 ——
-- 那时页面还没做，加了菜单上就会出现一个点进去什么都没有的入口。
-- 现在 tab 做好了，功能点才跟上。
--
-- 复用 message:template:read/update，不新增权限码：维护消息模板的与看发送记录的
-- 是同一批人，多一个码只增加配置负担。
--
-- 写成可重入形式（SELECT … FROM DUAL WHERE NOT EXISTS）：裸 VALUES 撞上唯一键就是
-- 1062，而重跑不是异常情况 —— 迁移中途失败、本地库来回切分支都会让它再跑一次。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MESSAGE__TAB_NOTIFYLOG', 'OPS_MESSAGE', '发送记录', '触达', '/messages?tab=notifyLog', 'message:template:read', 'message:template:read', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 20, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MESSAGE__TAB_NOTIFYLOG');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MESSAGE__TAB_NOTIFYLOG', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MESSAGE__TAB_NOTIFYLOG');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPPORT', 'OPS_MESSAGE__TAB_NOTIFYLOG', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='OPS_MESSAGE__TAB_NOTIFYLOG');

