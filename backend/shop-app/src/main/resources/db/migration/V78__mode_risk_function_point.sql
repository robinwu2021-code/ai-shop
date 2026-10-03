-- 新增功能点：运营端「无照自营风险」（商家治理 tab）。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 这条迁移为什么必须跟着 nav.ts 一起出
-- ─────────────────────────────────────────────────────────────────────────────
-- 判权读的是**库**（RolePermResolver → sys_function_point）。
-- 只改 ops-web/lib/nav.ts 不出迁移的话，这一项对**所有非超管不可见** ——
-- 页面在、路由在、代码在，就是看不见，而没有任何东西报错。
-- 守卫 packages/shared/tests/nav-function-point.test.ts 正是为这个装的。
--
-- ⚠️ **只插增量，不重灌全量种子。** gen-perm-seed.mjs 输出的是完整种子
-- （V62 那一份），整份再跑一次会与存量冲突。这里只取它为新叶子生成的三行。
--
-- 写法沿用 V74 的 `VALUES … ON DUPLICATE KEY UPDATE`：既幂等（开发库反复重放
-- 不会撞 1062），也能被 nav-function-point 守卫的解析器读出来。
-- 我最初写成 `INSERT … SELECT … WHERE NOT EXISTS` 且把列表换了行 ——
-- 语义没错，但守卫的正则要求表名与列表同行，于是它读不到这个功能点，
-- 反过来报「只在 nav 里」。**与既有写法保持一致比自己发明一种更重要。**
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 这个功能点是什么
-- ─────────────────────────────────────────────────────────────────────────────
-- 「没有营业执照的主体 × 按自营结算的门店」清单。自营意味着平台是法律上的
-- 销售主体，列支成本要取得进项发票，而无照主体开不出票 ——
-- 这笔支出**不得在企业所得税前扣除**。
--
-- 而这个组合是 mch_store.business_mode 默认自营 + 全仓无「无照禁自营」校验的
-- **必然结果**，不是配置失误。所以它是现状盘点，不是异常报表。
--
-- ⚠️ 权限给的是 merchant:mode:read（BD + 超管），**财务看不到** ——
-- 而这是一张税务表，财务本该是主要读者。给 FINANCE 加这个码要动
-- Perms.ROLE_PERMS 与权限矩阵基线，属于单独一次改动。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT__TAB_MODE_RISK', 'OPS_MERCHANT', '无照自营风险', '入驻与资质', '/merchants?tab=mode-risk', 'merchant:mode:read', 'merchant:mode:read', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 50, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT__TAB_MODE_RISK', 'OPS', NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT__TAB_MODE_RISK', 'OPS', NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
