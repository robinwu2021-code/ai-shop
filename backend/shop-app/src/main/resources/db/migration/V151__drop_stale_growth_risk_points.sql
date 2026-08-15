-- 收敛增长/风控两域的功能点：删旧点号 + 把既有点的 perm_code 补上。
--
-- 两个独立的坑，都是「插入是幂等的、而更新没人做」造成的：
--
-- ① **旧点号还在**。V72 把整套 OPS 功能点删了重建，序号式（OPS_GROWTH_01）换成语义式
--    （OPS_GROWTH / OPS_GROWTH__TAB_TRACES）。但 V62/V64/V65 里那批 INSERT 仍在迁移历史里，
--    而 `gen-test-schema.py` **只重放 INSERT、不执行 DELETE** ——
--    于是测试库里 V72 那次「删旧建新」只做了建新那一半，旧行长了回来。
--
-- ② **新点号的 perm_code 是 NULL**。V72 建 OPS_GROWTH 时增长域后端一个端点都没有，
--    所以它当时诚实地写成 `perm_code=NULL, backend_status=NOT_IMPLEMENTED`。
--    2026-08-13 增长域落地后 V136 补了种子，但它写的是 `WHERE NOT EXISTS` ——
--    **看到同名点就整条跳过，没有把 perm_code 更新过来**。
--    症状：`OpsPermConfigFlowTest.dbConfigMatchesHardcoded` 报 CAMPAIGN_OPS 少了
--    `growth:attribution:read` 与 `growth:fission:update`，而 Perms.java 与 V136 各自看都是对的。
--
-- 教训：**幂等插入只保证「不重复建」，不保证「值是对的」**。
-- 给一个已存在的配置行补新字段时，要显式 UPDATE，不能指望 INSERT 顺带做掉。
--
-- **不去改 V62/V64/V65/V72**：已发布的迁移不能改（别人的库已经按原样跑过）。

-- ── ① 删旧点号 ──
DELETE FROM sys_role_point
 WHERE point_code IN ('OPS_GROWTH_01', 'OPS_GROWTH_02', 'OPS_GROWTH_03',
                      'OPS_RISK_01', 'OPS_RISK_02', 'OPS_RISK_03');

DELETE FROM sys_function_point
 WHERE point_code IN ('OPS_GROWTH_01', 'OPS_GROWTH_02', 'OPS_GROWTH_03',
                      'OPS_RISK_01', 'OPS_RISK_02', 'OPS_RISK_03');

-- ── ② 把两域已落地的 perm_code 补到既有点上 ──
-- 只改这两域自己的点，且只在 perm_code 为空时改 —— 不覆盖别人配好的值。
UPDATE sys_function_point
   SET perm_code = ui_perm_code, backend_status = 'IMPLEMENTED', updated_at = NOW()
 WHERE point_code IN ('OPS_GROWTH', 'OPS_GROWTH__TAB_TRACES', 'OPS_GROWTH__TAB_FISSION',
                      'OPS_RISK', 'OPS_RISK__TAB_BLACKLIST', 'OPS_RISK__TAB_RULES')
   AND (perm_code IS NULL OR perm_code = '')
   AND ui_perm_code IS NOT NULL;
