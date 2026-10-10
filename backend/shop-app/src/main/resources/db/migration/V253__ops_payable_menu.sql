-- 运营端三个新菜单叶子：自营应付账款、进项票、买家开票申请。
--
-- **运营端菜单不是 nav.ts 说了算**：标签、分组、可见性都来自
-- `sys_function_point` / `sys_role_point`。只改 nav.ts 的话本地 mock 下看得见，
-- **接上真实后端就没有** —— 这是 2026-08-14 拆触达菜单时踩过的坑。
--
-- ⚠️ **sort 插空位，既有行一行不动。** 生成器（ops-web/scripts/gen-perm-seed.mjs）
-- 给的是 40 / 50 / 60，那是按 nav.ts 顺序全量重排的结果 —— 直接用会把
-- 既有的 TAB_POINTS(50) 顶掉，而那一行已经在生产库里。
-- 既有：TAB_POINTS=50、TAB_POINTS_POLICY=51（V251 也是这么插的）。本次取 52/53/54。
--
-- 这三条对应的后端端点**早已实现**（十个），此前运营端零入口 ——
-- 而这是今天唯一真能把钱付出去的路（第三方走分账，而分账网关是桩）。
-- 见 docs/requirements/PRD-商家资金到账与对账.md

INSERT INTO sys_function_point
    (point_code, function_code, name, group_name, href, ui_perm_code, perm_code,
     backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
VALUES
    ('OPS_FINANCE__TAB_PAYABLES', 'OPS_FINANCE', '自营应付账款', '应付与发票',
     '/finance?tab=payables', 'finance:settle:read', 'finance:settle:read',
     'IMPLEMENTED', 1, 'P-12.1', 'MENU', 52, NOW(), NOW()),
    ('OPS_FINANCE__TAB_PURCHASE_INVOICES', 'OPS_FINANCE', '进项票', '应付与发票',
     '/finance?tab=purchase-invoices', 'finance:invoice:read', 'finance:invoice:read',
     'IMPLEMENTED', 1, 'P-12.2', 'MENU', 53, NOW(), NOW()),
    ('OPS_FINANCE__TAB_BUYER_INVOICES', 'OPS_FINANCE', '买家开票申请', '应付与发票',
     '/finance?tab=buyer-invoices', 'finance:invoice:read', 'finance:invoice:read',
     'IMPLEMENTED', 1, 'P-12.2', 'MENU', 54, NOW(), NOW());

-- 角色可见性。SUPER_ADMIN 与 FINANCE 两个 —— 与既有 finance 页签同一套。
-- **AUDITOR 不给**：它是只读审计角色，而这三页上有确认对账/核验/开票三个写动作，
-- 给了之后按钮画出来点不动，比不给更让人困惑。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES
    ('SUPER_ADMIN', 'OPS_FINANCE__TAB_PAYABLES', 'OPS', NOW(), NOW()),
    ('SUPER_ADMIN', 'OPS_FINANCE__TAB_PURCHASE_INVOICES', 'OPS', NOW(), NOW()),
    ('SUPER_ADMIN', 'OPS_FINANCE__TAB_BUYER_INVOICES', 'OPS', NOW(), NOW()),
    ('FINANCE', 'OPS_FINANCE__TAB_PAYABLES', 'OPS', NOW(), NOW()),
    ('FINANCE', 'OPS_FINANCE__TAB_PURCHASE_INVOICES', 'OPS', NOW(), NOW()),
    ('FINANCE', 'OPS_FINANCE__TAB_BUYER_INVOICES', 'OPS', NOW(), NOW());
