-- =====================================================================
-- V392 撤「提现审批」菜单点（TDD-账期推进与放款记录 AC9 / PRD-商家资金到账与对账 §7）
--
-- 商家向平台提现是二清（ADR-011 §2）。这个入口 V112 建成，到撤掉为止生产 0 行；
-- 钱出去走「账期批次与放款」（V391 stl_payout）。
--
-- 做法：**删授权与菜单点，不删表、不删权限码。** stl_withdraw 与 WithdrawService 留着
-- （删表另起迁移），finance:withdraw:approve 仍在 Perms 里 —— 只是没有任何菜单点再引用它。
-- 先删 sys_role_point 再删 sys_function_point：反过来会留悬挂的授权行，
-- 而 check-perm-seed-drift 对的是种子 ↔ 库，悬挂行会被报成「库里多了」。
--
-- 写成可重入：撤掉的点不存在时两条 DELETE 都是 0 行，不报错。
-- =====================================================================

DELETE FROM sys_role_point WHERE point_code = 'OPS_FINANCE__TAB_WITHDRAW' AND end_code = 'OPS';

DELETE FROM sys_function_point WHERE point_code = 'OPS_FINANCE__TAB_WITHDRAW';
