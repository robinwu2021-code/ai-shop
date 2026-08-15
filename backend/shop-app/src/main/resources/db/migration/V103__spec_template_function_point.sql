-- 「规格模板」菜单点（/products?tab=templates，矩阵 P-3.4）。
--
-- 与 V99 同一套写法：`ops-web/scripts/gen-perm-seed.mjs` 是**全量重生成器**
-- （它重造 V72 的整份数据），不适合直接落成增量迁移。所以这里只逐字取出本次新增的行，
-- 其余一行不动。point_code / ui_perm_code / perm_code / matrix_code 与生成器输出一致。
--
-- sort 取 50：库里 OPS_PRODUCT 那组现有 10/20/30/40（类目树 / 商品池 / 审核队列 / 预售），
-- 新叶子排在最后，**既有行一行不碰** —— 插在中间要顺带改掉其后每一行，
-- 那正是「插一个叶子让其后全部右移」的老毛病。
--
-- 权限码复用 product:category:read（不新增码）：规格模板是**按类目预置**的，
-- 与类目树、资质码字典同一个维护面。维护动作本身走 product:category:update，
-- 由 ACT__PRODUCT_CATEGORY_UPDATE 那个 ACTION 点管，本迁移不涉及。
--
-- 写成可重入形式（SELECT … FROM DUAL WHERE NOT EXISTS）：裸 VALUES 撞上唯一键就是 1062，
-- 而重跑不是异常情况 —— 迁移中途失败、本地库来回切分支都会让它再跑一次。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_PRODUCT__TAB_TEMPLATES', 'OPS_PRODUCT', '规格模板维护', '规格模板', '/products?tab=templates', 'product:category:read', 'product:category:read', 'IMPLEMENTED', 1, 'P-3.4', 'MENU', 50, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_PRODUCT__TAB_TEMPLATES');

-- 超管：通配角色，但库里仍逐点关联（sys_role.wildcard 只是短路，配置表要能审）
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_PRODUCT__TAB_TEMPLATES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_PRODUCT__TAB_TEMPLATES');

-- 商品运营（GOODS_OPS）：他已经持有 product:category:read（类目树靠它）。
-- 漏掉这一行的表现是**静默降级** —— 菜单里没有这一项，而页面、路由、代码全在，
-- 且没有任何报错。E27 说的「模板是死的」正是这样死掉的。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'GOODS_OPS', 'OPS_PRODUCT__TAB_TEMPLATES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='GOODS_OPS' AND x.point_code='OPS_PRODUCT__TAB_TEMPLATES');
