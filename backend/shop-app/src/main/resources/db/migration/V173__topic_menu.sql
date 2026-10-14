-- 主题分类进运营端菜单（商品域-优化总方案 批 E / O7）。
--
-- 后端 `/ops/topics` ×4 在 V172 那一轮就通了，但**运营在界面上看不到它** ——
-- 这个仓库的运营端菜单在库里（`sys_function_point` + `sys_role_point`），
-- 只改 `lib/nav.ts` 而不落迁移，接真后端时那一栏根本不出现，
-- 而且 `@PreAuthorize` 判的也是库里这份：不落种子的话连超管都是 403。
--
-- 下面几行由 `node ops-web/scripts/gen-perm-seed.mjs` 生成，不手写：
-- 真源是 nav.ts（菜单树）× perm-map.ts（UI 码→后端码）× Perms.java（角色→码）。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_PRODUCT__TAB_TOPICS', 'OPS_PRODUCT', '主题分类', '陈列', '/products?tab=topics', 'product:topic:read', 'product:topic:read', 'IMPLEMENTED', 1, 'P-3.6', 'MENU', 70, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__PRODUCT_TOPIC_UPDATE', 'OPS_PRODUCT', 'product:topic:update', '页面内操作', NULL, 'product:topic:update', 'product:topic:update', 'IMPLEMENTED', 1, NULL, 'ACTION', 903, NOW(), NOW());

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT__TAB_TOPICS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__PRODUCT_TOPIC_UPDATE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT__TAB_TOPICS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT__PRODUCT_TOPIC_UPDATE', 'OPS', NOW(), NOW());
