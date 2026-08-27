-- 补 V263 漏掉的一半：`NOT_IMPLEMENTED` 的功能点不该挂后端权限码。
--
-- V263 把进销存三行置成 `NOT_IMPLEMENTED`（菜单灰显、不可点），但 `perm_code`
-- 还留着 V261 写的 `product:sku:read`。`OpsPermConfigFlowTest:114` 断言
-- 「未实现的功能点不该挂后端码」，于是那条从 V263 落库那天起就是红的 ——
-- 而它会挡住**所有人**涉及 backend/ 的推送（pre-push 最后那道
-- check-head-compiles 与 known-failures.txt 比对，这条不在名单里）。
--
-- ⚠️ **`ui_perm_code` 不动。** 菜单还要渲染（灰显也要先渲染出来），
-- 而渲染判的是它；`perm_code` 判的是后端闸门，一个点不动的菜单没有后端闸门可判。
--
-- ⚠️ **`perm_code` 那一列的注释写着「NULL = 不受权限约束（谁都能用）」。**
-- 对一个不可点的灰菜单无害，但**翻亮那天必须连它一起还原**，
-- 否则三个 /ops/inventory/** 在权限矩阵里就成了无码的。V263 只说了改
-- backend_status，那是不够的 —— 完整的翻亮语句在下面，照抄即可：
--
--   UPDATE sys_function_point
--   SET backend_status = 'IMPLEMENTED',
--       perm_code      = 'product:sku:read',
--       updated_at     = NOW()
--   WHERE point_code IN ('OPS_INVENTORY', 'OPS_INVENTORY__TAB_LEDGER', 'OPS_INVENTORY__TAB_RECON');
--
-- （V263 说这条「见 进销存-上线与G3切换手册.md」，而那份手册里没有这一节 ——
--   指向一节不存在的文档比不指更糟，照着去翻的人会以为自己找漏了。本次一并补上。）
--
-- **待议，不在这一笔里做**：`NOT_IMPLEMENTED` 这一档现在同时装着两种东西 ——
-- 「没写」和「写了但线上开关关着」。进销存属于后者：三个端点在代码里是存在的，
-- 只是挂着 @ConditionalOnProperty(shop.inventory.enabled)。
-- 那条断言（未实现的不该挂后端码）对后者本来就不成立，所以这一笔是**止血**：
-- 它让闸门绿，没让模型对。将来只要还有「代码在、线上关着」的功能，就会原样再撞一次。

UPDATE sys_function_point
SET perm_code  = NULL,
    updated_at = NOW()
WHERE point_code IN ('OPS_INVENTORY', 'OPS_INVENTORY__TAB_LEDGER', 'OPS_INVENTORY__TAB_RECON')
  AND backend_status = 'NOT_IMPLEMENTED';
