-- 套餐商城运营端菜单点（ops-web /mall 七个页签，Task 5）。
--
-- 权限码本身由 V556 / V558 预置（commerce:*，只授予 SUPER_ADMIN）；本迁移只补**菜单行**：
-- 运营端接真实后端后菜单来自 /ops/menu（sys_function_point.href），没有这些行
-- 页面在线上根本没有入口（ops-web/lib/nav.test.ts「每个叶子的 href 在迁移里有 sys_function_point 行」）。
--
-- point_code / name / group_name / href / perm_code / matrix_code 逐字取自
-- `node ops-web/scripts/gen-perm-seed.mjs` 的输出（point_code 在 ops-web/lib/point-codes.ts 冻结为 OPS_COMMERCE*，
-- 不用 href 派生：/mall 挂在 merchant 分区下会派生成 OPS_MERCHANT，与「入驻审核」撞键）。
-- sort 取 82~88：插在库里既有的「档位定义」与「链条画像」之间，既有行一行不碰。
--
-- 授权只给 SUPER_ADMIN，与 V556/V558 的码一致；其余角色要用，由管理员在角色页显式授予。
-- 可重入（SELECT … FROM DUAL WHERE NOT EXISTS，与 V99 同一写法）：迁移中途失败或本地库来回切分支都会重跑。


INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_COMMERCE', 'OPS_MERCHANT', '经营类型与套餐', '套餐商城', '/mall', 'commerce:catalog:read', 'commerce:catalog:read', 'IMPLEMENTED', 1, 'P-11.2', 'MENU', 82, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_COMMERCE');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_COMMERCE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_COMMERCE');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_COMMERCE__TAB_PRODUCTS', 'OPS_MERCHANT', '商城商品与SKU', '套餐商城', '/mall?tab=products', 'commerce:catalog:read', 'commerce:catalog:read', 'IMPLEMENTED', 1, 'P-11.2', 'MENU', 83, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_COMMERCE__TAB_PRODUCTS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_COMMERCE__TAB_PRODUCTS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_COMMERCE__TAB_PRODUCTS');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_COMMERCE__TAB_ORDERS', 'OPS_MERCHANT', '商城订单', '套餐商城', '/mall?tab=orders', 'commerce:mall:order:read', 'commerce:mall:order:read', 'IMPLEMENTED', 1, 'P-11.2', 'MENU', 84, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_COMMERCE__TAB_ORDERS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_COMMERCE__TAB_ORDERS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_COMMERCE__TAB_ORDERS');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_COMMERCE__TAB_CLAIMS', 'OPS_MERCHANT', '线下核款', '套餐商城', '/mall?tab=claims', 'commerce:mall:offline:review', 'commerce:mall:offline:review', 'IMPLEMENTED', 1, 'P-11.2', 'MENU', 85, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_COMMERCE__TAB_CLAIMS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_COMMERCE__TAB_CLAIMS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_COMMERCE__TAB_CLAIMS');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_COMMERCE__TAB_COLLECTION', 'OPS_MERCHANT', '平台收款档案', '套餐商城', '/mall?tab=collection', 'commerce:collection:profile:read', 'commerce:collection:profile:read', 'IMPLEMENTED', 1, 'P-11.2', 'MENU', 86, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_COMMERCE__TAB_COLLECTION');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_COMMERCE__TAB_COLLECTION', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_COMMERCE__TAB_COLLECTION');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_COMMERCE__TAB_ASSISTED', 'OPS_MERCHANT', '代客线下购买', '套餐商城', '/mall?tab=assisted', 'commerce:mall:assisted:purchase', 'commerce:mall:assisted:purchase', 'IMPLEMENTED', 1, 'P-11.2', 'MENU', 87, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_COMMERCE__TAB_ASSISTED');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_COMMERCE__TAB_ASSISTED', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_COMMERCE__TAB_ASSISTED');

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_COMMERCE__TAB_SOURCES', 'OPS_MERCHANT', '门店权益来源', '套餐商城', '/mall?tab=sources', 'commerce:subscription:read', 'commerce:subscription:read', 'IMPLEMENTED', 1, 'P-11.2', 'MENU', 88, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_COMMERCE__TAB_SOURCES');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_COMMERCE__TAB_SOURCES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_COMMERCE__TAB_SOURCES');
