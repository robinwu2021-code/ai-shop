-- 「账期批次与放款」「商家欠款」两项的功能点。
--
-- **只改 nav.ts 不发这条迁移的话，这两项对所有非超管都是不可见的** ——
-- 而页面、路由、接口全都在，症状是「财务说他看不到那一页」。
-- 超管看得见（sys_role.wildcard 短路），于是自测永远发现不了。
-- （nav.test.ts 有一条守卫盯着这件事，正是它把这两项拦下来的。）
--
-- 两项都判 finance:settle:read，与「结算单与分账」同一档：
-- 能看结算单的人就该看得到这些钱卡在哪一批、谁还欠着。
-- **能不能动手是另一回事**：放行判 finance:settle:execute、
-- 保证金抵扣判 finance:payout:execute，那两条在接口上，不在菜单上。
--
-- 写法用 SELECT … FROM DUAL WHERE NOT EXISTS：裸 VALUES 撞上 uk_point 是 1062，
-- 迁移重跑必炸。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_FINANCE__TAB_SETTLE_BATCHES', 'OPS_FINANCE', '账期批次与放款', '分账结算', '/finance?tab=settle-batches', 'finance:settle:read', 'finance:settle:read', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 12, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_FINANCE__TAB_SETTLE_BATCHES');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_FINANCE__TAB_DEBTS', 'OPS_FINANCE', '商家欠款', '分账结算', '/finance?tab=debts', 'finance:settle:read', 'finance:settle:read', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 13, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_FINANCE__TAB_DEBTS');

-- 超管：通配角色，但库里仍逐点关联（wildcard 只是短路，配置表要能审）
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_FINANCE__TAB_SETTLE_BATCHES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_FINANCE__TAB_SETTLE_BATCHES');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_FINANCE__TAB_DEBTS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_FINANCE__TAB_DEBTS');

-- 财务：他本来就持有 finance:settle:read。
-- 漏掉这两行的表现是**静默降级** —— 菜单里没有这两项，而权限码是够的。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'FINANCE', 'OPS_FINANCE__TAB_SETTLE_BATCHES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='FINANCE' AND x.point_code='OPS_FINANCE__TAB_SETTLE_BATCHES');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'FINANCE', 'OPS_FINANCE__TAB_DEBTS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='FINANCE' AND x.point_code='OPS_FINANCE__TAB_DEBTS');
