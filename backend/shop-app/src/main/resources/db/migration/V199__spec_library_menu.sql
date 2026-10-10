-- 规格库分家：三个新菜单点 + 一个新权限码的动作点。
--
-- 与 V99/V103 同一套写法：`gen-perm-seed.mjs` 是全量重生成器，不适合直接落成增量迁移，
-- 所以只逐字取出本次新增的行，其余一行不碰。写成可重入形式 —— 迁移中途失败、
-- 本地库来回切分支都会让它再跑一次，而裸 VALUES 撞唯一键就是 1062。
--
-- 为什么新开 product:spec:*：类目权限还兼着资质门槛（required_code 决定一整类商品的准入），
-- 而规格库改一条会影响所有商家的建品页。两件事的授权范围不该绑在一起 ——
-- 配规格的人不必有权放宽准入，改准入的人也不必天天进规格库。
--
-- sort 取 60/61/62：OPS_PRODUCT 那组现有 10–50（类目树/商品池/审核队列/预售/规格模板），
-- 新叶子排在最后，既有行一行不动。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_PRODUCT__TAB_SPEC_COMMON', 'OPS_PRODUCT', '通用规格', '规格', '/products?tab=spec-common', 'product:spec:read', 'product:spec:read', 'IMPLEMENTED', 1, 'P-3.4', 'MENU', 60, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_PRODUCT__TAB_SPEC_COMMON');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_PRODUCT__TAB_SPEC_SPECIAL', 'OPS_PRODUCT', '专用规格', '规格', '/products?tab=spec-special', 'product:spec:read', 'product:spec:read', 'IMPLEMENTED', 1, 'P-3.4', 'MENU', 61, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_PRODUCT__TAB_SPEC_SPECIAL');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_PRODUCT__TAB_CATEGORY_SPEC', 'OPS_PRODUCT', '类目 × 规格', '规格', '/products?tab=category-spec', 'product:spec:read', 'product:spec:read', 'IMPLEMENTED', 1, 'P-3.4', 'MENU', 62, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_PRODUCT__TAB_CATEGORY_SPEC');

-- 维护动作单独一个 ACTION 点：与「看得到」分开，配角色时才能给只读
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__PRODUCT_SPEC_UPDATE', 'OPS_PRODUCT', '规格库维护', '规格', NULL, 'product:spec:update', 'product:spec:update', 'IMPLEMENTED', 1, 'P-3.4', 'ACTION', 63, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__PRODUCT_SPEC_UPDATE');

-- 超管：通配角色，但库里仍逐点关联（sys_role.wildcard 只是短路，配置表要能审）
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', p.point_code, 'OPS', NOW(), NOW() FROM sys_function_point p
 WHERE p.point_code IN ('OPS_PRODUCT__TAB_SPEC_COMMON','OPS_PRODUCT__TAB_SPEC_SPECIAL','OPS_PRODUCT__TAB_CATEGORY_SPEC','ACT__PRODUCT_SPEC_UPDATE')
   AND NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code=p.point_code);

-- 商品运营（GOODS_OPS）：规格库是他的日常。漏掉这几行的表现是**静默降级** ——
-- 菜单里没有这几项，而页面、路由、代码全在，且没有任何报错。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'GOODS_OPS', p.point_code, 'OPS', NOW(), NOW() FROM sys_function_point p
 WHERE p.point_code IN ('OPS_PRODUCT__TAB_SPEC_COMMON','OPS_PRODUCT__TAB_SPEC_SPECIAL','OPS_PRODUCT__TAB_CATEGORY_SPEC','ACT__PRODUCT_SPEC_UPDATE')
   AND NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='GOODS_OPS' AND x.point_code=p.point_code);
