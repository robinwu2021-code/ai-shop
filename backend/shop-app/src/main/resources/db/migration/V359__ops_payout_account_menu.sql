-- 运营端菜单叶子：供应商收款账户（V358 的审核入口，ADR-011）。
--
-- **运营端菜单不是 nav.ts 说了算**：标签、分组、可见性都来自
-- `sys_function_point` / `sys_role_point`。只改 nav.ts 的话本地 mock 下看得见，
-- **接上真实后端就没有**。而且 `useNavTabs` 在开发期会对没登记的 tab 直接抛错。
--
-- ⚠️ **sort 插空位，既有行一行不动。** 生成器（ops-web/scripts/gen-perm-seed.mjs）
-- 这次给的是 50，那是按 nav.ts 顺序全量重排的结果 —— 直接用会把既有的
-- TAB_PURCHASE_INVOICES(53) 与 TAB_BUYER_INVOICES(54) 一起顶掉，而那两行已经在生产库里。
-- 既有：TAB_PAYABLES=52、TAB_PURCHASE_INVOICES=53、TAB_BUYER_INVOICES=54（V253）。本次取 55。
-- nav.ts 里的位置也一并挪到这一组的末尾 —— 两处顺序不一致的话，
-- 本地 mock 与真后端看到的菜单排列不同，而那种差异没人会想到去对。
--
-- 权限码复用 finance:payout:execute（登记付款），不新开码：
-- 审核收款账户就是决定下一期的钱打到哪里，与把钱付出去是同一个岗位的一体两面。

INSERT INTO sys_function_point
    (point_code, function_code, name, group_name, href, ui_perm_code, perm_code,
     backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
VALUES
    ('OPS_FINANCE__TAB_PAYOUT_ACCOUNTS', 'OPS_FINANCE', '供应商收款账户', '应付与发票',
     '/finance?tab=payout-accounts', 'finance:payout:execute', 'finance:payout:execute',
     'IMPLEMENTED', 1, 'P-12.1', 'MENU', 55, NOW(), NOW());

-- 角色可见性。与 V253 同一套：SUPER_ADMIN 与 FINANCE。
-- **AUDITOR 不给**：它是只读审计角色，而这一页上的动作是通过/驳回一张收款卡 ——
-- 给了之后按钮画出来点不动，比不给更让人困惑。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES
    ('SUPER_ADMIN', 'OPS_FINANCE__TAB_PAYOUT_ACCOUNTS', 'OPS', NOW(), NOW()),
    ('FINANCE', 'OPS_FINANCE__TAB_PAYOUT_ACCOUNTS', 'OPS', NOW(), NOW());

-- ⚠️ **既有的 ACT__FINANCE_PAYOUT_EXECUTE 刻意不删。**
--
-- 那是个「仅承载授权」的 ACTION 点：后端有 finance:payout:execute 这个码、
-- 而运营端此前没有界面。现在有了界面，生成器的期望态里它确实消失了
-- （菜单项 130→131、页面内操作 44→43，总数不变就是这么来的）。
--
-- 但**删它有真风险而留它无害**：
-- · 风险：sys_role_point 里可能挂着运营在生产上自建的角色，
--   删点位会让那些角色失去这个码，而且不报错。
-- · 无害：权限判定读的是 perm_code 不是 point_code，两个点挂同一个码，
--   角色从哪一个拿到都一样；ACTION 点没有 href，不会在菜单上多出一行。
--
-- 代价是生成器的全量输出与库会差这一行。这是增量迁移与「从零建库」之间
-- 的正常历史差异，不是缺陷 —— 真要对齐，该做的是把授权迁移过来再删，
-- 那是一次独立的清理，不该夹在一个加菜单的迁移里。
