-- 三个新菜单叶子：类目 × 支付方式、类目 × 积分、积分端开关。
--
-- **运营端菜单不是 nav.ts 说了算**：标签、分组、可见性都来自
-- `sys_function_point` / `sys_role_point`。只改 nav.ts 的话，本地 mock 下看得见，
-- **接上真实后端就没有** —— 这是 2026-08-14 拆触达菜单时踩过的坑。
--
-- 生成方式与 V99 / V103 / V140 同一套：跑 `ops-web/scripts/gen-perm-seed.mjs`
-- （它是全量重生成器，不适合直接落成迁移），从输出里**逐字取出**本次新增的行。
-- point_code / ui_perm_code / perm_code / matrix_code 与生成器输出一致，
-- 下次全量重生成时不会漂移。
--
-- ⚠️ **既有行一行不动，sort 插空位。** 生成器给的是 90 / 100 / 50，那是按 nav.ts
-- 重排出来的号；照抄会把它们甩到组尾，也可能与别人后加的行撞上。
-- 这里取 63 / 64（「类目 × 规格」62 与「主题分类」70 之间）与 51
-- （「积分资金看板」50 与「发票与个税」60 之间），谁都不右移。
--
-- 可重入形式（SELECT … WHERE NOT EXISTS）：迁移中途失败、本地库来回切分支
-- 都会让它再跑一次，裸 VALUES 撞唯一键就是 1062。

-- ── 1) 菜单叶子 ──
--
-- 权限码复用 product:category:read / finance:settle:read，**不新增码**：
--   · 类目策略两页跟**类目**走不跟规格走 —— 改「这一类能不能当面付」与
--     改规格绑定不是同一类决定，配规格的人不该顺手拿到前者。
--   · 端开关挂在结算码下而不是营销码下：关掉一个端的发放，减少的是
--     **平台对用户的负债**，那是资金决定不是活动决定。
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_PRODUCT__TAB_CATEGORY_PAY_MODE', 'OPS_PRODUCT', '类目 × 支付方式', '类目', '/products?tab=category-pay-mode', 'product:category:read', 'product:category:read', 'IMPLEMENTED', 1, 'P-3.1', 'MENU', 63, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_PRODUCT__TAB_CATEGORY_PAY_MODE');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_PRODUCT__TAB_CATEGORY_POINTS', 'OPS_PRODUCT', '类目 × 积分', '类目', '/products?tab=category-points', 'product:category:read', 'product:category:read', 'IMPLEMENTED', 1, 'P-3.1', 'MENU', 64, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_PRODUCT__TAB_CATEGORY_POINTS');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_FINANCE__TAB_POINTS_POLICY', 'OPS_FINANCE', '积分端开关', '分账结算', '/finance?tab=points-policy', 'finance:settle:read', 'finance:settle:read', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 51, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_FINANCE__TAB_POINTS_POLICY');

-- ── 2) 角色授权：与生成器输出一致 ──
--
-- ⚠️ **逐行写，不用 CROSS JOIN**：测试 schema 由 gen-test-schema.py 重放迁移生成，
-- 它只认单行的 `INSERT ... SELECT ... FROM DUAL WHERE NOT EXISTS`。
-- 写成 CROSS JOIN 的话，H2 那份 schema 里**一条授权都不会有** ——
-- 症状是 superAdminHasEveryPoint 少 N 条，而迁移本身在 MySQL 上是对的。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_PRODUCT__TAB_CATEGORY_PAY_MODE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_PRODUCT__TAB_CATEGORY_PAY_MODE');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'GOODS_OPS', 'OPS_PRODUCT__TAB_CATEGORY_PAY_MODE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='GOODS_OPS' AND x.point_code='OPS_PRODUCT__TAB_CATEGORY_PAY_MODE');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_PRODUCT__TAB_CATEGORY_POINTS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_PRODUCT__TAB_CATEGORY_POINTS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'GOODS_OPS', 'OPS_PRODUCT__TAB_CATEGORY_POINTS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='GOODS_OPS' AND x.point_code='OPS_PRODUCT__TAB_CATEGORY_POINTS');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_FINANCE__TAB_POINTS_POLICY', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_FINANCE__TAB_POINTS_POLICY');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'FINANCE', 'OPS_FINANCE__TAB_POINTS_POLICY', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='FINANCE' AND x.point_code='OPS_FINANCE__TAB_POINTS_POLICY');
