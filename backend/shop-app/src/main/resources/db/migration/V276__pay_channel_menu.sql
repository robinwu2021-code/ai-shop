-- 「支付通道与费率」这一项的功能点。
--
-- ⚠️ 本文件原本是 V275，与场景×通道那条线的 V275 撞号 —— 两个会话在同一个目录里
-- 各自查过「最大号」，而对方的迁移落在我查完之后、提交之前。
-- H2 测试不跑 Flyway，所以本机全绿；撞号只在**下一次真库启动**时暴露：
-- 「Found more than one migration with version 275」，服务直接起不来。
--
-- **只改 nav.ts 不发这条迁移的话，这一项对所有非超管都是不可见的** ——
-- 而页面、路由、接口全都在，症状是「财务说他看不到那一页」。
-- 超管看得见（sys_role.wildcard 短路），于是自测永远发现不了。
--
-- 授权范围照 OPS_FINANCE__TAB_RATES 那一项：超管 + 财务。
-- 两者都判 finance:rate:update —— 能调佣金的人就是能调通道费率的人。
--
-- 写法用 SELECT … FROM DUAL WHERE NOT EXISTS：裸 VALUES 撞上 uk_point 是 1062，
-- 迁移重跑必炸。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_FINANCE__TAB_PAY_CHANNELS', 'OPS_FINANCE', '支付通道与费率', '费率', '/finance?tab=pay-channels', 'finance:rate:update', 'finance:rate:update', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 41, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_FINANCE__TAB_PAY_CHANNELS');

-- 超管：通配角色，但库里仍逐点关联（wildcard 只是短路，配置表要能审）
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_FINANCE__TAB_PAY_CHANNELS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_FINANCE__TAB_PAY_CHANNELS');

-- 财务：他本来就持有 finance:rate:update（隔壁那一栏靠的就是它）。
-- 漏掉这一行的表现是**静默降级** —— 菜单里没有这一项，而权限码是够的。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'FINANCE', 'OPS_FINANCE__TAB_PAY_CHANNELS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='FINANCE' AND x.point_code='OPS_FINANCE__TAB_PAY_CHANNELS');
