-- 运营端「进销存」独立菜单：库存健康度、库存流水、库存对差。
--
-- **独立成一个功能（OPS_INVENTORY），不挂在 OPS_PRODUCT 下面。**
-- 进销存有独立的库、独立的 Java 模块（shop-inventory），将来要能单独交付；
-- 在菜单里把它做成商品页的 tab，等于在界面上先把这条边界抹掉 ——
-- 而抹掉之后没有人会记得它曾经存在。
--
-- **运营端菜单不是 nav.ts 说了算**：标签、分组、可见性都来自
-- `sys_function_point` / `sys_role_point`。只改 nav.ts 的话本地 mock 下看得见，
-- 接上真实后端就没有。
--
-- 各行由 ops-web/scripts/gen-perm-seed.mjs 从 nav.ts + perm-map.ts + Perms.java
-- 派生，此处只改了 sort（见下）。
--
-- ⚠️ **sort 插空位，既有行一行不动。** 生成器给 OPS_INVENTORY 的是 50，
-- 那是按 nav.ts 顺序全量重排的结果 —— 而 50 是 OPS_ORDER 的位置，
-- 直接用会把订单菜单顶掉。取 45，落在 OPS_PRODUCT(40) 与 OPS_ORDER(50) 之间。
--
-- **ui_perm_code 与 perm_code 同为 product:sku:read**，这不是偷懒：
-- 三个端点上的 @PreAuthorize 判的就是它（OpsInventoryController 两个 +
-- OpsInventoryReconController 一个）。界面闸门与后端闸门错开的后果是
-- 「菜单点得进、进去一片 403」，而看的人只会认为功能坏了。
-- 也没有新造 inventory:* 码：新码要同时改 Perms.java 与角色授权，
-- 少任何一头的表现都是「这个菜单谁都看不见」，而那看起来像菜单没生成。

INSERT INTO sys_function
    (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at)
VALUES ('OPS_INVENTORY', '进销存', 'OPS', 'Boxes', '/inventory', 45, 1, NOW(), NOW());

INSERT INTO sys_function_point
    (point_code, function_code, name, group_name, href, ui_perm_code, perm_code,
     backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
VALUES
    ('OPS_INVENTORY', 'OPS_INVENTORY', '库存健康度', '库存治理',
     '/inventory', 'product:sku:read', 'product:sku:read',
     'IMPLEMENTED', 1, 'P-18.1', 'MENU', 10, NOW(), NOW()),
    ('OPS_INVENTORY__TAB_LEDGER', 'OPS_INVENTORY', '库存流水', '库存治理',
     '/inventory?tab=ledger', 'product:sku:read', 'product:sku:read',
     'IMPLEMENTED', 1, 'P-18.2', 'MENU', 20, NOW(), NOW()),
    ('OPS_INVENTORY__TAB_RECON', 'OPS_INVENTORY', '库存对差', '切换判据',
     '/inventory?tab=recon', 'product:sku:read', 'product:sku:read',
     'IMPLEMENTED', 1, 'P-18.3', 'MENU', 30, NOW(), NOW());

-- 角色可见性：与既有商品页签同一套（SUPER_ADMIN / GOODS_OPS / AUDITOR）。
-- **AUDITOR 给**：它是只读审计角色，而这三页一个写动作都没有 ——
-- 正是它该看的东西。（V253 那轮不给 AUDITOR，是因为那三页上有确认对账/核验/
-- 开票三个写动作，给了之后按钮画出来点不动，比不给更让人困惑。）
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES
    ('SUPER_ADMIN', 'OPS_INVENTORY', 'OPS', NOW(), NOW()),
    ('SUPER_ADMIN', 'OPS_INVENTORY__TAB_LEDGER', 'OPS', NOW(), NOW()),
    ('SUPER_ADMIN', 'OPS_INVENTORY__TAB_RECON', 'OPS', NOW(), NOW()),
    ('GOODS_OPS', 'OPS_INVENTORY', 'OPS', NOW(), NOW()),
    ('GOODS_OPS', 'OPS_INVENTORY__TAB_LEDGER', 'OPS', NOW(), NOW()),
    ('GOODS_OPS', 'OPS_INVENTORY__TAB_RECON', 'OPS', NOW(), NOW()),
    ('AUDITOR', 'OPS_INVENTORY', 'OPS', NOW(), NOW()),
    ('AUDITOR', 'OPS_INVENTORY__TAB_LEDGER', 'OPS', NOW(), NOW()),
    ('AUDITOR', 'OPS_INVENTORY__TAB_RECON', 'OPS', NOW(), NOW());
