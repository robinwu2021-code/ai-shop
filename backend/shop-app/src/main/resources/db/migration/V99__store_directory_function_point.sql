-- 门店档案菜单点（/merchants?tab=stores，矩阵 P-11.2）。
--
-- **生成方式**：`ops-web/scripts/gen-perm-seed.mjs` 是全量重生成器（它重造 V62 的
-- 整份数据），不适合直接落成增量迁移。所以这里的做法是：跑一遍生成器，从它的输出里
-- **逐字取出**本次新增的那三行（一个功能点 + 两条角色授权），其余一行不动。
-- 三行的 point_code / ui_perm_code / perm_code / matrix_code 与生成器输出一致，
-- 下次全量重生成时不会漂移。
--
-- 唯一的改动是 `sort`：生成器按叶子在 nav.ts 里的位置重排（门店档案 30、类目授权 40…），
-- 而库里 V72 之后 类目授权 已经是 30。跟着生成器写会让两条撞在同一个 sort 上，
-- **且要顺带改掉其后每一行** —— 那正是「插一个叶子让其后全部右移」的老毛病。
-- 取 25 插在 商家档案(20) 与 类目授权(30) 之间，既有行一行不碰。
--
-- 权限码复用 merchant:merchant:read（不新增码）：门店档案答的是「这家商家的哪家店」，
-- 与商家档案是同一批人、同一次动作的两步。**解除强制下线**那个按钮另走
-- merchant:merchant:ban，它是页面内动作，由 ACT__MERCHANT_MERCHANT_BAN 那个点管，
-- 本迁移不涉及。
--
-- 写成可重入形式（SELECT … FROM DUAL WHERE NOT EXISTS）：裸 VALUES 撞上唯一键就是
-- 1062，而重跑不是异常情况 —— 迁移中途失败、本地库来回切分支都会让它再跑一次。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MERCHANT__TAB_STORES', 'OPS_MERCHANT', '门店档案', '入驻与资质', '/merchants?tab=stores', 'merchant:merchant:read', 'merchant:merchant:read', 'IMPLEMENTED', 0, 'P-11.2', 'MENU', 25, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MERCHANT__TAB_STORES');

-- 超管：通配角色，但库里仍逐点关联（sys_role.wildcard 只是短路，配置表要能审）
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MERCHANT__TAB_STORES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MERCHANT__TAB_STORES');

-- 商家运营（BD）：他已经持有 merchant:merchant:read（商家档案/信用档案都靠它），
-- 漏掉这一行的表现是**静默降级** —— 菜单里没有这一项，而页面、路由、代码全在。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'BD', 'OPS_MERCHANT__TAB_STORES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='OPS_MERCHANT__TAB_STORES');
