-- 「进件看板」这一项的功能点（WS-C）。
--
-- ⚠️ **只改 nav.ts 不发这条迁移的话，这一项对所有非超管都是不可见的** ——
-- 页面、路由、接口全都在（GET /ops/onboarding），症状是「BD 说他看不到那一页」。
-- 超管看得见（sys_role.wildcard 短路），于是自测永远发现不了。运营端菜单在库里，
-- nav.ts 只是本地 mock 的形状。
--
-- ⚠️ 撞号风险：本文件取 V289，与 V288（另一会话的 market_and_test_channel）相邻。
-- 若同目录另一会话也落了 V289，真库启动会 "Found more than one migration with
-- version 289"（H2 测试不跑 Flyway，本机全绿，只在下一次真库启动暴露）——
-- 撞了就改号并 clean package。
--
-- 授权范围照 OPS_MERCHANT__TAB_ADMISSION 那一项：超管 + 财务。进件看板复用
-- merchant:admission:read（进件与准入是同一拨人管：都决定这家店能不能真把生意做成）。
--
-- 写法用 SELECT … FROM DUAL WHERE NOT EXISTS：裸 VALUES 撞上 uk_point 是 1062，
-- 迁移重跑必炸。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MERCHANT__TAB_ONBOARDING', 'OPS_MERCHANT', '进件看板', '入驻与资质', '/merchants?tab=onboarding', 'merchant:admission:read', 'merchant:admission:read', 'IMPLEMENTED', 1, 'P-11.1', 'MENU', 41, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MERCHANT__TAB_ONBOARDING');

-- 超管：通配角色，但库里仍逐点关联（wildcard 只是短路，配置表要能审）
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MERCHANT__TAB_ONBOARDING', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MERCHANT__TAB_ONBOARDING');

-- 财务：本来就持有 merchant:admission:read（准入那一项靠的就是它）。
-- 漏掉这一行的表现是静默降级 —— 菜单里没有这一项，而权限码是够的。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'FINANCE', 'OPS_MERCHANT__TAB_ONBOARDING', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='FINANCE' AND x.point_code='OPS_MERCHANT__TAB_ONBOARDING');
