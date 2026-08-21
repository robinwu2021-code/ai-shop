-- 标准品库进运营端菜单（TDD-标准品库 §3.4 / 商品域-优化总方案 O2）。
--
-- 后端 `/ops/spu-std` ×4 在 V166 那一轮就通了，但**运营在界面上看不到它** ——
-- 这个仓库的运营端菜单在库里（`sys_function_point` + `sys_role_point`），
-- 只改 `lib/nav.ts` 而不落迁移，接真后端时那一栏根本不出现。
--
-- 下面几行由 `node ops-web/scripts/gen-perm-seed.mjs` 生成，不手写：
-- 真源是 nav.ts（菜单树）× perm-map.ts（UI 码→后端码）× Perms.java（角色→码）。
--
-- 权限码单开 `product:std:*` 而不复用 `product:category:*`：
-- 类目决定「这类货要什么资质」（准入门槛），标准品决定「这件货长什么样」（录入模板）。
-- 让能改准入的人才能录标准品，会把一件运营日常挡在一个很高的门后面。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_PRODUCT__TAB_SPU_STD', 'OPS_PRODUCT', '标准品库', '标准品', '/products?tab=spu-std', 'product:std:read', 'product:std:read', 'IMPLEMENTED', 1, 'P-3.5', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__PRODUCT_STD_UPDATE', 'OPS_PRODUCT', 'product:std:update', '页面内操作', NULL, 'product:std:update', 'product:std:update', 'IMPLEMENTED', 1, NULL, 'ACTION', 902, NOW(), NOW());

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT__TAB_SPU_STD', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__PRODUCT_STD_UPDATE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT__TAB_SPU_STD', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT__PRODUCT_STD_UPDATE', 'OPS', NOW(), NOW());
