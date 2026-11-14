-- 套餐商城「商城商品分类」页签的菜单点（ops-web /mall?tab=categories；commerce V11 mall_category）。
--
-- 运营端接真实后端后 tab 条来自 /ops/menu（sys_function_point.href），没有这一行线上就没有入口。
-- 字段逐字取自 `node ops-web/scripts/gen-perm-seed.mjs`（point_code 在 ops-web/lib/point-codes.ts 冻结）。
-- 权限码沿用 commerce:catalog:read（V556/V558 已预置），不新增权限码；授权只给 SUPER_ADMIN，与 V628 一致。
--
-- 位置：紧跟「经营类型与套餐」(82)。V628 的 83~88 依次后移一位到 84~89（89 空着，链条画像在 90）；
-- 只移**仍是 V628 默认值**的行 —— 运营在配置页拖过的顺序不动。可重入。

UPDATE sys_function_point SET sort = 89, updated_at = NOW() WHERE point_code = 'OPS_COMMERCE__TAB_SOURCES'    AND sort = 88;
UPDATE sys_function_point SET sort = 88, updated_at = NOW() WHERE point_code = 'OPS_COMMERCE__TAB_ASSISTED'   AND sort = 87;
UPDATE sys_function_point SET sort = 87, updated_at = NOW() WHERE point_code = 'OPS_COMMERCE__TAB_COLLECTION' AND sort = 86;
UPDATE sys_function_point SET sort = 86, updated_at = NOW() WHERE point_code = 'OPS_COMMERCE__TAB_CLAIMS'     AND sort = 85;
UPDATE sys_function_point SET sort = 85, updated_at = NOW() WHERE point_code = 'OPS_COMMERCE__TAB_ORDERS'     AND sort = 84;
UPDATE sys_function_point SET sort = 84, updated_at = NOW() WHERE point_code = 'OPS_COMMERCE__TAB_PRODUCTS'   AND sort = 83;

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_COMMERCE__TAB_CATEGORIES', 'OPS_MERCHANT', '商城商品分类', '套餐商城', '/mall?tab=categories', 'commerce:catalog:read', 'commerce:catalog:read', 'IMPLEMENTED', 1, 'P-11.2', 'MENU', 83, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_COMMERCE__TAB_CATEGORIES');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_COMMERCE__TAB_CATEGORIES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_COMMERCE__TAB_CATEGORIES');
