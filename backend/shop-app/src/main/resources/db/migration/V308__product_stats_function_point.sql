-- 商品统计菜单点（/products?tab=stats，M4）。
--
-- **此前商品域一个统计数字都没有**（缺口清单里商品③ 是 0/5），而商品是这个平台的主体：
-- 骨架（类目、规格库、标准品）画得最全，却答不出「画的这些骨架有多少真的被用上了」。
-- 线上实测：73 个类目只有 14 个被用过，209 个 SKU 里 1 个有条码 ——
-- 后一个数就是扫码入库能覆盖的上限，而它此前没有一处显示。
--
-- 自成一组「统计」，sort 取 80，落在既有最大值(70)之后 —— 既有行一行不动，
-- 且同 group 的叶子仍然相邻（它是这个菜单里唯一一页只读不改的）。
--
-- perm 复用 product:sku:read，不新增码：它只读，且读的正是商品池那一批数据的汇总。
-- 后端 OpsProductStatsController 判的是同一个码。
--
-- 授权照着「主题分类」那一页的同一批：超管 + 商品运营。
-- **审核员也该看得到**（他持有 product:sku:read，而通过率与积压正是他的工作面），
-- 所以按 perm_code 相同的既有点去派生，而不是写死两行 —— 写死会漏掉他。

INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_PRODUCT__TAB_STATS', 'OPS_PRODUCT', '商品统计', '统计', '/products?tab=stats', 'product:sku:read', 'product:sku:read', 'IMPLEMENTED', 1, 'P-3.2', 'MENU', 80, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_PRODUCT__TAB_STATS');

-- 授权**逐行写死，不从既有点派生**。
--
-- 第一版写的是「JOIN sys_function_point 找出所有 perm_code = product:sku:read 的
-- MENU 点，把它们的角色抄过来」—— 逻辑没错，但 `gen-test-schema.py`
-- **静默丢掉了带 JOIN 的 INSERT…SELECT**（V306 里不带 JOIN 的那条就活下来了）。
-- 后果是测试库里这一行不存在、生产库里存在，而 OpsPermConfigFlowTest 当场变红。
-- 聪明的派生换不来什么，写死三行反而看得见授给了谁。
--
-- 三个角色都持有 product:sku:read：超管、商品运营、审核员。
-- **审核员必须在里面** —— 通过率与积压正是他的工作面。

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_PRODUCT__TAB_STATS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_PRODUCT__TAB_STATS');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'GOODS_OPS', 'OPS_PRODUCT__TAB_STATS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='GOODS_OPS' AND x.point_code='OPS_PRODUCT__TAB_STATS');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'AUDITOR', 'OPS_PRODUCT__TAB_STATS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='AUDITOR' AND x.point_code='OPS_PRODUCT__TAB_STATS');
