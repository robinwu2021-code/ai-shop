-- 「场景与通道」菜单项（/messages?tab=routing）。
--
-- 后端的 GET/POST /ops/scene-channel 早就有（P-14.1），运营端一直没有入口 ——
-- 于是「哪个事件走哪些通道」这份配置存在、能改、有审计，却没人看得见。
-- 页面这一批才补上，功能点跟着落库。
--
-- **只改 nav.ts 是不够的**：线上菜单以 sys_function_point 为准，
-- 少了这一行，那个叶子接真后端时根本不出现（守卫 nav-function-point 会拦下来）。
--
-- 复用 message:template:read / update，不新增权限码：配模板的与配触达通道的是同一批人。
-- 读用 read、写用 update —— 与后端两个端点判的码一一对应，
-- 免得出现「菜单看得见、点开只读」或者反过来「按钮亮着点了 403」。
--
-- 可重入写法（SELECT … FROM DUAL WHERE NOT EXISTS）：重跑不是异常情况 ——
-- 迁移中途失败、本地库来回切分支都会让它再跑一次，裸 VALUES 撞唯一键就是 1062。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MESSAGE__TAB_ROUTING', 'OPS_MESSAGE', '场景与通道', '触达', '/messages?tab=routing', 'message:template:read', 'message:template:read', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 19, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MESSAGE__TAB_ROUTING');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MESSAGE__TAB_ROUTING', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MESSAGE__TAB_ROUTING');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPPORT', 'OPS_MESSAGE__TAB_ROUTING', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='OPS_MESSAGE__TAB_ROUTING');
