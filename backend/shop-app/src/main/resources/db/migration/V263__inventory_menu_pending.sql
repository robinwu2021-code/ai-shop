-- 进销存菜单先置灰，等这一块在线上真的能用了再翻亮。
--
-- **为什么不是「不加菜单」**：V261 已经把三行写进去了，而 `sys_function_point`
-- 是运营端菜单的真源。留着不管的话，部署当天菜单就出现了 ——
-- 而三个 `/ops/inventory/**` 端点挂着 `@ConditionalOnProperty(shop.inventory.enabled)`，
-- 线上那个开关是关的、进销存库也还没建。
-- 结果是**菜单点得进、进去一片「加载失败」**，那比没有这个菜单更糟：
-- 它不像「还没做」，像「做坏了」。
--
-- `NOT_IMPLEMENTED` 就是为这一档准备的（见 V62 建表注释）：
-- 菜单灰显 + 待建角标、**不可点**。ops-web 的 `secondary-nav` 按它渲染
-- （`isPointUnimplemented`）。禁用项从一开始就说明了自己不能用，
-- 而死按钮是「看着能点、点了出错」。
--
-- **翻亮的条件**（四件事全成立，缺一件商家/运营看到的都是空或错）：
--   1. 建进销存库（需要 DBA）
--   2. `shop.inventory.enabled=true`
--   3. worker profile 上线 —— 否则搬运任务不跑，库里一条物料都不会有
--   4. `backfill.enabled=true` 且 `dry-run=false`，跑到 `pending=0` 且 `(到末尾)`
--
-- 翻亮就是一条 UPDATE 把这三行改回 IMPLEMENTED，见
-- docs/technical/design/进销存-上线与G3切换手册.md

UPDATE sys_function_point
SET backend_status = 'NOT_IMPLEMENTED',
    updated_at     = NOW()
WHERE point_code IN ('OPS_INVENTORY', 'OPS_INVENTORY__TAB_LEDGER', 'OPS_INVENTORY__TAB_RECON');
