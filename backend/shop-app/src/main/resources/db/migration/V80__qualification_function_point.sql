-- 新增功能点：运营端「资质档案」（商家治理 tab）。
--
-- 判权读的是库，只改 ops-web/lib/nav.ts 不出迁移的话，这一项对所有非超管不可见 ——
-- 页面在、路由在、代码在，就是看不见，而没有任何东西报错。
-- 守卫 packages/shared/tests/nav-function-point.test.ts 正是为这个装的。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 这一页补的是什么
-- ─────────────────────────────────────────────────────────────────────────────
-- 上架的两个闸门 ——「资质过期」（QualificationExpiryJob + hasExpiredQualification）
-- 与「类目授权」（sys_auth_code.required_qualification）—— 读的都是 mch_qualification。
--
-- 而那张表**实测 0 行**：入驻收的执照停在申请单里没人转存（V79 已接上自动链路），
-- 后端三个管理接口早就实现了，**前端一个调用方都没有**。
-- 两个闸门都写好了、都从不触发，且不报任何错。这一页补的是人工那条路：
-- 补录历史资质、证件换发后更新、作废时撤销。
--
-- ⚠️ 同时补了 UI_PERM_MAP 里 merchant:category:read 的映射 ——
-- 这是同一个坑第二次（上一次是 merchant:mode:read）：后端有码、映射表没登记，
-- can() 一律判无权限，于是有权限的人在界面上什么也看不到，**且不报错**。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT__TAB_QUALIFICATIONS', 'OPS_MERCHANT', '资质档案', '入驻与资质', '/merchants?tab=qualifications', 'merchant:category:read', 'merchant:category:read', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 40, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT__TAB_QUALIFICATIONS', 'OPS', NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT__TAB_QUALIFICATIONS', 'OPS', NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
