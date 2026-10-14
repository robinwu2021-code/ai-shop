-- 运营端菜单叶子：经营统计（结算口径的三维聚合，TDD-供应商结算与双轨资金 §2.1）。
--
-- **运营端菜单不是 nav.ts 说了算**：标签、分组、可见性都来自
-- `sys_function_point` / `sys_role_point`；而且 `useNavTabs` 开发期会对
-- 没登记的 tab 直接抛错。
--
-- ⚠️ **sort 取 21，插在 TAB_SPLITS(20) 之后的空位。**
-- 生成器按 nav.ts 顺序全量重排，直接用它给的数会把 REFUND_BACK(30)、
-- RATES(40)、POINTS(50) 一路顶掉，而那些行都已经在生产库里。
-- 既有分布：20 SPLITS · 30 REFUND_BACK · 40 RATES · 50 POINTS/WITHDRAW ·
-- 51 POINTS_POLICY · 52~55 应付与发票组。21 是空的。
--
-- 权限用 finance:settle:read（结算读），与分账明细同一个码：
-- 两页都是「看这段时间结算了多少」，没有「能看明细不能看合计」这种岗位。

INSERT INTO sys_function_point
    (point_code, function_code, name, group_name, href, ui_perm_code, perm_code,
     backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
VALUES
    ('OPS_FINANCE__TAB_SETTLE_STATS', 'OPS_FINANCE', '经营统计', '分账结算',
     '/finance?tab=settle-stats', 'finance:settle:read', 'finance:settle:read',
     'IMPLEMENTED', 1, 'P-12.1', 'MENU', 21, NOW(), NOW());

-- 角色可见性。**比收款账户那条多给一个 AUDITOR**：这一页是纯只读的统计，
-- 没有任何写动作，而审计角色本来就该看得到钱的去向。
-- （V359 的收款账户不给 AUDITOR，是因为那页上有通过/驳回两个写动作。）
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES
    ('SUPER_ADMIN', 'OPS_FINANCE__TAB_SETTLE_STATS', 'OPS', NOW(), NOW()),
    ('FINANCE', 'OPS_FINANCE__TAB_SETTLE_STATS', 'OPS', NOW(), NOW());
