-- 内容位落地：把「首页楼层与 Banner」那个菜单点从「后端未实现」改成真的挂上权限码。
--
-- **生成方式**：`ops-web/scripts/gen-perm-seed.mjs` 是全量重生成器（重造 V62 的整份数据），
-- 不适合直接落成增量迁移。做法与 V99 / V301 相同：跑一遍生成器，从输出里逐字取出
-- 与本次相关的那几行，其余一行不动。
--
-- 这一条与 V301 的区别：那个点是**新增**的，这个点 V62 就有 ——
-- 它一直躺在库里、perm_code 为空、backend_status='NOT_IMPLEMENTED'。
-- 于是运营端菜单里它对**除超管以外的所有人隐藏**（角色授权按 perm_code 推），
-- 而页面、路由、mock 全都在。所以这里是 UPDATE 而不是 INSERT。
--
-- **两个码要两个功能点**，不是一个。库里「角色 → 后端权限码」是顺着功能点推出来的
-- （OpsPermConfigFlowTest#dbConfigMatchesHardcoded 逐条比对它与 Perms.ROLE_PERMS），
-- 所以只挂 update 那一个点的话，持有 marketing:slot:read 的角色在库里就查不出这个码 ——
-- 表现是那道守卫变红，而线上的表现是**商品运营看不到这一页**（他只有 read）。
-- 菜单点带 update（它是可写的那一页），read 走生成器同样会产出的 ACTION 点。
--
-- 写成可重入形式：迁移中途失败、本地库来回切分支都会让它再跑一次。

UPDATE sys_function_point
   SET ui_perm_code = 'marketing:slot:update',
       perm_code = 'marketing:slot:update',
       backend_status = 'IMPLEMENTED',
       updated_at = NOW()
 WHERE point_code = 'OPS_MARKETING__TAB_SLOTS';

-- 活动运营：他持有 marketing:slot:update（Perms.java 里同批加的）。
-- 漏掉这一行的表现是**静默降级** —— 菜单里没有这一项，而功能全做好了。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'CAMPAIGN_OPS', 'OPS_MARKETING__TAB_SLOTS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='OPS_MARKETING__TAB_SLOTS');

-- 超管：通配角色，但库里仍逐点关联（sys_role.wildcard 只是短路，配置表要能审）
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MARKETING__TAB_SLOTS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MARKETING__TAB_SLOTS');

-- 只读码的 ACTION 点（生成器会产出同样一行；point_code / sort 与它逐字一致）
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__MARKETING_SLOT_READ', 'OPS_MARKETING', 'marketing:slot:read', '页面内操作', NULL, 'marketing:slot:read', 'marketing:slot:read', 'IMPLEMENTED', 1, NULL, 'ACTION', 912, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__MARKETING_SLOT_READ');

-- 商品运营只拿只读：首页推的是商品，他要查得到「这件货为什么在第一屏」，
-- 但配版位是内容运营的活（所以他没有 TAB_SLOTS 那个菜单点）
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'GOODS_OPS', 'ACT__MARKETING_SLOT_READ', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='GOODS_OPS' AND x.point_code='ACT__MARKETING_SLOT_READ');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'CAMPAIGN_OPS', 'ACT__MARKETING_SLOT_READ', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='ACT__MARKETING_SLOT_READ');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__MARKETING_SLOT_READ', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__MARKETING_SLOT_READ');
