-- 商家链条画像菜单点（/merchants?tab=chain，M1）。
--
-- **运营端菜单不是 nav.ts 说了算** —— 标签、分组、可见性都来自
-- `sys_function_point` / `sys_role_point`。只改 nav.ts 的话本地 mock 下看得见，
-- 接上真实后端就没有，而那看起来像页面写坏了。
-- （nav.test.ts 有一道闸专门拦这个：叶子的 href 在迁移里找不到行就红。）
--
-- 照 V99 的做法：可重入形式，sort 插在既有行之后，一行既有数据都不碰。
-- 取 90，落在「增值包与额度」(80) 之后 —— 它自成一组「经营诊断」，
-- 而**同 group 的叶子必须相邻**，插在中间会把「入驻与资质」劈成两半。
--
-- 权限码复用 merchant:merchant:read，不新增码：这一页答的是「今天该找哪家商家」，
-- 与商家档案是同一批人、同一次动作的两步。后端 OpsMerchantChainController
-- 判的是同一个码 —— 界面闸门比后端松，表现就是「菜单点得进、进去一片 403」。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MERCHANT__TAB_CHAIN', 'OPS_MERCHANT', '链条画像', '经营诊断', '/merchants?tab=chain', 'merchant:merchant:read', 'merchant:merchant:read', 'IMPLEMENTED', 1, 'P-11.1', 'MENU', 90, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MERCHANT__TAB_CHAIN');

-- 超管：通配角色，但库里仍逐点关联（sys_role.wildcard 只是短路，配置表要能审）
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MERCHANT__TAB_CHAIN', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MERCHANT__TAB_CHAIN');

-- 商家运营（BD）：**这一页就是给他的**。链条画像的结论是「今天该找哪家商家」，
-- 而找到之后打电话、发消息、看档案的人就是 BD。
-- 漏掉这一行的表现是静默降级 —— 菜单里没有这一项，而页面、路由、代码全在。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'BD', 'OPS_MERCHANT__TAB_CHAIN', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='OPS_MERCHANT__TAB_CHAIN');
