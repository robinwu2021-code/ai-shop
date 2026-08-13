-- 新增功能点：运营端「积分资金看板」（结算与资金 tab）。
--
-- 判权读的是库 —— 只改 nav.ts 不出迁移，这一项对所有非超管不可见，
-- 而页面在、路由在、代码在，没有任何东西报错。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 这一页补的是什么
-- ─────────────────────────────────────────────────────────────────────────────
-- PointsService.overview 一直在那里（流通积分 / 池子余额 / 按通道分账本），
-- 而运营端**一个积分接口都没有** —— 池子对不对得上，只能连数据库看。
--
-- 恒等式是「流通中的积分 == 池子里的钱」。两个数分开看的话，
-- 失衡要等到有人主动比对才会发现；这一页把差额直接算出来，并给出方向：
--   池子多了 → 收了钱没发出对应的分
--   池子少了 → 发了分没收到对应的钱
-- 方向本身是信息，两种要查的地方不同。
--
-- ⚠️ **只读，不给写侧。** 池子的钱是靠流水推出来的，不是靠人改的 ——
-- 开一个「手工调整余额」的入口，等于允许在没有业务事件的情况下改账，
-- 而那之后恒等式失衡就再也说不清是哪一笔了。要调整就补一笔有类型的流水。
--
-- 权限用 finance:settle:read 而不是营销侧的码：**这是一张资金表**，
-- 读它的是财务。FINANCE 角色本来就持有它，所以这一页对财务开箱可见。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE__TAB_POINTS', 'OPS_FINANCE', '积分资金看板', '分账结算', '/finance?tab=points', 'finance:settle:read', 'finance:settle:read', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 50, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE__TAB_POINTS', 'OPS', NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE__TAB_POINTS', 'OPS', NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
