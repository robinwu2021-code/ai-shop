-- 类目补到「开箱即用的一整套」，剩下的交给运营开关（承 V175/V176）。
--
-- ⚠️ 本条原本编号 V179，与并行会话的 `V179__demo_community_region.sql` 撞号 ——
-- **撞号自己让路**（共享工作树的规矩：未提交的那一方改）。改到 V190 时
-- 迁移目录已经排到 V189，取当前最大号 +1，别再挑一个中间的空位。
--
-- 做法上的取舍：**宁可多备、默认停用，也不要让运营从零建**。
-- 建一个类目要同时想清楚形态、门槛码、层级与排序 —— 那是四个都能悄悄写错的字段
-- （形态写错让一箱啤酒要求填截单时间；门槛码写错就是一个永不命中的门槛）。
-- 备好之后，运营的动作收敛成一个开关：这一类我们这一期做不做。

-- ── 1. 水产海鲜的授权码 ───────────────────────────────────────
--
-- 单开而不是复用 FOOD（熟食加工）：两者要的证虽然同名，但经营范围不同，
-- 复用会让「只批了熟食」的店顺带能卖活鱼。码是判据，判据要与现实一一对应。
INSERT INTO sys_auth_code
(code, name, required_qualification, sort, enabled, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('FRESH_AQUATIC', '水产海鲜', '食品经营许可证', 18, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);

-- ── 2. 食品生鲜补两件（默认启用）─────────────────────────────
--
-- 熟食卤味在**生产库里早就有**（运营手工建的），但迁移种子里没有 ——
-- 于是新环境与生产不是同一棵树，而这种差异只有在「线上有、本地没有」时才被发现。
-- 这条把它补进种子，两边就此对齐。
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT140' AS category_no, 'CAT100' AS parent_no, 2 AS level, '熟食卤味' AS name, 'Deli' AS name_en,
 NULL AS icon, 40 AS sort, 'STANDARD' AS template, NULL AS attr_template,
 '["食品经营许可证"]' AS qualification_required, 'FOOD' AS required_code, 'ACTIVE' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT140');

INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('CAT190', 'CAT100', 2, '水产海鲜', 'Seafood', NULL, 90, 'FRESH', NULL,
 '["食品经营许可证"]', 'FRESH_AQUATIC', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT290', 'CAT200', 2, '厨房用具', 'Kitchenware', NULL, 90, 'STANDARD', NULL,
 NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);

-- ── 3. 备着但**默认停用**的那些 ───────────────────────────────
--
-- 判据：社区里确实有人做，但**不是第一批要主推的**。
-- 停用态的类目：商家选不到、C 端不出现，运营在类目页一个开关就能放出来。
--
-- 停用而不是干脆不建：不建的话，运营想开这一类时要自己拼形态与门槛码，
-- 而那正是最容易写错、且错了不报错的地方。
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
-- 服务：社区里都有，但接单与履约链路（上门时段、师傅）比零售重，放二期
('CAT370', 'CAT300', 2, '洗车养护', 'Car Wash',        NULL, 70, 'SERVICE', NULL, NULL, NULL, 'ARCHIVED', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT380', 'CAT300', 2, '开锁换锁', 'Locksmith',       NULL, 80, 'SERVICE', NULL, NULL, NULL, 'ARCHIVED', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT390', 'CAT300', 2, '家电清洗', 'Appliance Clean', NULL, 90, 'SERVICE', NULL, NULL, NULL, 'ARCHIVED', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
-- 服饰：**退换率高、尺码复杂**，与「楼下拿了就走」的心智不合 —— 备着，先不开
('CAT900', NULL,     1, '服饰鞋帽', 'Apparel',      NULL, 90, 'STANDARD', NULL, NULL, NULL, 'ARCHIVED', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT910', 'CAT900', 2, '内衣袜子', 'Underwear',    NULL, 10, 'STANDARD', NULL, NULL, NULL, 'ARCHIVED', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT920', 'CAT900', 2, '鞋类拖鞋', 'Shoes',        NULL, 20, 'STANDARD', NULL, NULL, NULL, 'ARCHIVED', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT930', 'CAT900', 2, '家纺床品', 'Home Textile', NULL, 30, 'STANDARD', NULL, NULL, NULL, 'ARCHIVED', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
