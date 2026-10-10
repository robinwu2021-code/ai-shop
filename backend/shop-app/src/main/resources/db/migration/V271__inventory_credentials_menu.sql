-- 运营端进销存第四页「开放对接」的菜单行。
--
-- **这一条是补账，而它暴露的是一次真实的静默失败。**
-- 2026-08-29 15:27 后端与 ops-web 一起上线，页面代码、接口、权限码全对，
-- 面包屑也认得「进销存 › 对外 › 开放对接」—— 但页签条上只有三个，
-- 第四个静静地不在。因为 tab 的可见性除了判权，还要求这条 href
-- 出现在服务端菜单里（visibleTabKeys 读 serverHrefs），而库里没有这一行。
--
-- 本地 mock 下四个页签齐全，所以整个开发与验证过程里一次都没露过馅：
-- **mock 不读这张表**。这正是 V261 那段注释已经写过的事
-- （「只改 nav.ts 本地看得见，接上真实后端就没有」），我又踩了一遍。
-- 这次在 ops-web/lib/nav.test.ts 里补了闸门：nav.ts 的每个叶子都必须
-- 在迁移里找得到 sys_function_point 行，找不到就红。
--
-- ⚠️ sort 取 40，落在 OPS_INVENTORY__TAB_RECON(30) 之后，既有行一行不动。

INSERT INTO sys_function_point
    (point_code, function_code, name, group_name, href, ui_perm_code, perm_code,
     backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
VALUES
    ('OPS_INVENTORY__TAB_CREDENTIALS', 'OPS_INVENTORY', '开放对接', '对外',
     '/inventory?tab=credentials', 'product:sku:read', 'product:sku:read',
     'IMPLEMENTED', 1, 'P-18.4', 'MENU', 40, NOW(), NOW());

-- 角色可见性：与另外三页**同一套**（SUPER_ADMIN / GOODS_OPS / AUDITOR）。
--
-- 这里与 V261 的取舍不同，值得写清楚。V253 的理由是「页上有写动作就不给
-- AUDITOR，否则按钮画出来点不动」—— 而这一页确实有写动作（签发、吊销）。
-- 但**按钮已经按 merchant:mode:update 判权藏掉了**（credentials-tab.tsx），
-- 三个角色里只有 SUPER_ADMIN 持有它。于是 GOODS_OPS 与 AUDITOR 得到的是
-- 一个干净的只读视图：**有哪些钥匙发出去过、谁在用、哪些已经吊销** ——
-- 那正是审计该看的东西，也是「密钥泄露了要查」时第一个要打开的页面。
--
-- ⚠️ **BD 的错位是已知的，这里不处理。** BD 持有 merchant:mode:update
-- （真正能发钥匙的那个码），却没有 product:sku:read，于是这一页对它不可见。
-- 补它要给 BD 加一个商品域的码，那是权限模型的改动，不该顺手塞进这条迁移里。
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES
    ('SUPER_ADMIN', 'OPS_INVENTORY__TAB_CREDENTIALS', 'OPS', NOW(), NOW()),
    ('GOODS_OPS',   'OPS_INVENTORY__TAB_CREDENTIALS', 'OPS', NOW(), NOW()),
    ('AUDITOR',     'OPS_INVENTORY__TAB_CREDENTIALS', 'OPS', NOW(), NOW());
