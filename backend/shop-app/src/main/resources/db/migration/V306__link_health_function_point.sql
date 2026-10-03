-- 投影链路健康度菜单点（/inventory?tab=link-health，M3）。
--
-- **为什么它不该继续待在「库存对差」里**：2026-09-02 投递任务停着，一条
-- SKU_UPSERTED 在队列里躺了六个小时 —— 那件货在库存里不存在，商家看不到、
-- 盘不着、进不了货，而任何地方都不会报错。它当时在运营端的唯一痕迹，
-- 是「库存对差」里的「待搬 1 个」：**一个链路问题被折叠进了一个数据指标**，
-- 而那个指标每天只跑一次。看到「待搬 1 个」的人推断不出「投递链路断了」。
--
-- 自成一组「链路」，sort 取 35，落在 库存对差(30) 与 开放对接(40) 之间 ——
-- 既有行一行不动，且同 group 的叶子仍然相邻（切换判据 / 链路 / 对外 各自成段）。
--
-- perm 用 inventory:stock:read：**这是 V272 之后的码**，不是 V261 建表时那批
-- product:sku:read（V272 把三页整体搬进了 inventory: 命名空间）。
-- 照着旧迁移抄会让这一页比邻居松一档，而症状是「审核员看得到别的三页、
-- 看不到这一页」这类说不清的差异。后端 OpsLinkHealthController 判的是同一个码。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_INVENTORY__TAB_LINK_HEALTH', 'OPS_INVENTORY', '链路健康', '链路', '/inventory?tab=link-health', 'inventory:stock:read', 'inventory:stock:read', 'IMPLEMENTED', 1, 'P-18.5', 'MENU', 35, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_INVENTORY__TAB_LINK_HEALTH');

-- 授权照着 V261 给那三页的同一批角色：超管、商品运营、审核员。
-- **不新增任何角色** —— 谁看得到库存对差，谁就该看得到链路健康，
-- 因为后者正是前者那个数字的解释。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT r.role_code, 'OPS_INVENTORY__TAB_LINK_HEALTH', 'OPS', NOW(), NOW()
  FROM sys_role_point r
 WHERE r.point_code = 'OPS_INVENTORY__TAB_RECON' AND r.end_code = 'OPS'
   AND NOT EXISTS (SELECT 1 FROM sys_role_point x
                    WHERE x.role_code = r.role_code
                      AND x.point_code = 'OPS_INVENTORY__TAB_LINK_HEALTH'
                      AND x.end_code = 'OPS');
