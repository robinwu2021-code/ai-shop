-- 触达按通道拆菜单的五个新叶子（TDD-运营端触达中心 §3.2）。
--
-- **生成方式**：与 V99 / V103 同一套写法 —— `ops-web/scripts/gen-perm-seed.mjs`
-- 是全量重生成器（它重造 V62 的整份数据），不适合直接落成增量迁移。
-- 这里跑一遍生成器，从输出里**逐字取出**本次新增的行，其余一行不动。
-- point_code / ui_perm_code / perm_code / matrix_code 与生成器输出一致，
-- 下次全量重生成时不会漂移。
--
-- ⚠️ **不改既有行**：生成器给的 sort 是按 nav.ts 里的新顺序重排的
-- （通道总览 10、短信 20…发送记录 70），而库里 OPS_MESSAGE 那组现有的
-- 三条（原「消息模板与推送」10 / 发送记录 20 / 工单 30 / FAQ 40）不动 ——
-- 跟着生成器改会顺带右移其后每一行，那正是「插一个叶子让其后全部右移」的老毛病。
-- 新叶子取 21–25，插在 发送记录(20) 之后、工单(30) 之前。
--
-- 为什么原「消息模板与推送」那条不改名：它的 href 是 `/messages`，
-- 而 `/messages` 现在是通道总览。名字对不上是真的 —— 所以这里**改它的 name**，
-- 这是本迁移唯一改动的既有行，且只改展示名，不动 point_code 与权限。
--
-- 权限码全部复用 message:template:read（不新增码）：看四条通道的人
-- 与维护模板的是同一批，多一个码只增加配置负担（与既有注释同一判断）。
--
-- 可重入形式（SELECT … WHERE NOT EXISTS）：迁移中途失败、本地库来回切分支
-- 都会让它再跑一次，裸 VALUES 撞唯一键就是 1062。

-- 1) 原「消息模板与推送」现在是通道总览（href 未变，只正名）
UPDATE sys_function_point SET name = '通道总览', updated_at = NOW()
 WHERE point_code = 'OPS_MESSAGE' AND name = '消息模板与推送';

-- 2) 四条通道 + 站内信，各一个菜单叶子
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MESSAGE__TAB_SMS', 'OPS_MESSAGE', '短信', '触达', '/messages?tab=sms', 'message:template:read', 'message:template:read', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 21, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MESSAGE__TAB_SMS');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MESSAGE__TAB_MAIL', 'OPS_MESSAGE', '邮件', '触达', '/messages?tab=mail', 'message:template:read', 'message:template:read', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 22, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MESSAGE__TAB_MAIL');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MESSAGE__TAB_WXSUB', 'OPS_MESSAGE', '微信订阅消息', '触达', '/messages?tab=wxsub', 'message:template:read', 'message:template:read', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 23, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MESSAGE__TAB_WXSUB');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MESSAGE__TAB_APPPUSH', 'OPS_MESSAGE', 'App 推送', '触达', '/messages?tab=apppush', 'message:template:read', 'message:template:read', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 24, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MESSAGE__TAB_APPPUSH');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MESSAGE__TAB_INAPP', 'OPS_MESSAGE', '站内信模板与推送任务', '触达', '/messages?tab=inapp', 'message:template:read', 'message:template:read', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 25, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MESSAGE__TAB_INAPP');

-- 3) 角色授权：与生成器输出一致 —— SUPER_ADMIN 与 SUPPORT（客服）。
-- 客服要看得到通道状态：用户说「没收到验证码」时，第一步就是看短信通道通不通。
--
-- **逐行写，不用 CROSS JOIN**：测试 schema 由 gen-test-schema.py 重放迁移生成，
-- 它只认单行的 `INSERT ... SELECT ... FROM DUAL WHERE NOT EXISTS` 形式。
-- 用 CROSS JOIN 写成 10 行一条语句时，H2 那份 schema 里**一条授权都不会有** ——
-- 症状是 superAdminHasEveryPoint 少 5 条，而迁移本身在 MySQL 上是对的（实测）。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MESSAGE__TAB_SMS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MESSAGE__TAB_SMS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MESSAGE__TAB_MAIL', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MESSAGE__TAB_MAIL');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MESSAGE__TAB_WXSUB', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MESSAGE__TAB_WXSUB');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MESSAGE__TAB_APPPUSH', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MESSAGE__TAB_APPPUSH');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MESSAGE__TAB_INAPP', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MESSAGE__TAB_INAPP');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPPORT', 'OPS_MESSAGE__TAB_SMS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='OPS_MESSAGE__TAB_SMS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPPORT', 'OPS_MESSAGE__TAB_MAIL', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='OPS_MESSAGE__TAB_MAIL');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPPORT', 'OPS_MESSAGE__TAB_WXSUB', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='OPS_MESSAGE__TAB_WXSUB');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPPORT', 'OPS_MESSAGE__TAB_APPPUSH', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='OPS_MESSAGE__TAB_APPPUSH');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPPORT', 'OPS_MESSAGE__TAB_INAPP', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='OPS_MESSAGE__TAB_INAPP');
