-- 运营端进销存三个只读菜单叶子：库存健康度、库存流水、库存对差。
--
-- **运营端菜单不是 nav.ts 说了算**：标签、分组、可见性都来自
-- `sys_function_point` / `sys_role_point`。只改 nav.ts 的话本地 mock 下看得见，
-- 接上真实后端就没有。
--
-- 三行都由 ops-web/scripts/gen-perm-seed.mjs 从 nav.ts + perm-map.ts + Perms.java
-- 派生，此处只改了 sort（见下）。
--
-- ⚠️ **sort 插空位，既有行一行不动。** 生成器给的是 50/60/70 —— 那是按 nav.ts 顺序
-- 全量重排的结果，直接用会把既有的 TAB_TEMPLATES(50) / TAB_SPU_STD(60) /
-- TAB_TOPICS(70) 顶掉，而那三行已经在生产库里。
-- 既有：TAB_STOCK=40、TAB_TEMPLATES=50。本次取 41/42/43，落在「库存与预售」组里
-- 紧跟 TAB_STOCK 之后。
--
-- **ui_perm_code 与 perm_code 同为 product:sku:read**，这不是偷懒：
-- 三个端点上的 @PreAuthorize 判的就是它（OpsInventoryController 两个 +
-- OpsInventoryReconController 一个）。界面闸门与后端闸门错开的后果是
-- 「菜单点得进、进去一片 403」，而看的人只会认为功能坏了。
-- 隔壁 TAB_STOCK 的 ui 码 product:stock:update 是界面自己的码、后端并不存在，别照抄。

INSERT INTO sys_function_point
    (point_code, function_code, name, group_name, href, ui_perm_code, perm_code,
     backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
VALUES
    ('OPS_PRODUCT__TAB_INV_HEALTH', 'OPS_PRODUCT', '库存健康度', '库存与预售',
     '/products?tab=inv-health', 'product:sku:read', 'product:sku:read',
     'IMPLEMENTED', 1, 'P-3.3', 'MENU', 41, NOW(), NOW()),
    ('OPS_PRODUCT__TAB_INV_LEDGER', 'OPS_PRODUCT', '库存流水', '库存与预售',
     '/products?tab=inv-ledger', 'product:sku:read', 'product:sku:read',
     'IMPLEMENTED', 1, 'P-3.3', 'MENU', 42, NOW(), NOW()),
    ('OPS_PRODUCT__TAB_INV_RECON', 'OPS_PRODUCT', '库存对差', '库存与预售',
     '/products?tab=inv-recon', 'product:sku:read', 'product:sku:read',
     'IMPLEMENTED', 1, 'P-3.3', 'MENU', 43, NOW(), NOW());

-- 角色可见性：与既有商品页签同一套（SUPER_ADMIN / GOODS_OPS / AUDITOR）。
-- **AUDITOR 这次给**：V253 那轮不给它，是因为那三页上有确认对账/核验/开票三个写动作，
-- 给了之后按钮画出来点不动。这三页一个写动作都没有，正是只读审计角色该看的东西。
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES
    ('SUPER_ADMIN', 'OPS_PRODUCT__TAB_INV_HEALTH', 'OPS', NOW(), NOW()),
    ('SUPER_ADMIN', 'OPS_PRODUCT__TAB_INV_LEDGER', 'OPS', NOW(), NOW()),
    ('SUPER_ADMIN', 'OPS_PRODUCT__TAB_INV_RECON', 'OPS', NOW(), NOW()),
    ('GOODS_OPS', 'OPS_PRODUCT__TAB_INV_HEALTH', 'OPS', NOW(), NOW()),
    ('GOODS_OPS', 'OPS_PRODUCT__TAB_INV_LEDGER', 'OPS', NOW(), NOW()),
    ('GOODS_OPS', 'OPS_PRODUCT__TAB_INV_RECON', 'OPS', NOW(), NOW()),
    ('AUDITOR', 'OPS_PRODUCT__TAB_INV_HEALTH', 'OPS', NOW(), NOW()),
    ('AUDITOR', 'OPS_PRODUCT__TAB_INV_LEDGER', 'OPS', NOW(), NOW()),
    ('AUDITOR', 'OPS_PRODUCT__TAB_INV_RECON', 'OPS', NOW(), NOW());
