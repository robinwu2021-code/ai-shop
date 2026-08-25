-- 类目按「一般社区电商」的常见盘子补齐，**全部默认停用**（承 V175/V176/V190）。
--
-- 做法沿用 V190 的取舍：**宁可多备、默认停用，也不要让运营从零建**。
-- 建一个类目要同时想清楚形态、门槛码、层级与排序 —— 四个字段都能悄悄写错
-- （形态写错会让一箱啤酒要求填截单时间；门槛码写错就是一个永不命中的门槛）。
-- 备好之后，运营的动作收敛成一个开关：这一类我们这一期做不做。
--
-- 三处取舍，写在这里免得以后当成漏做：
--
-- 1. **已有商品不自动迁。** 「洗护清洁」是从「纸品清洁」里分出来的，
--    但这条迁移**不动任何在售商品的 category_no** —— 类目决定形态与门槛，
--    自动迁等于悄悄改掉一批在售商品的经营门槛。要迁由运营启用后自己决定。
--
-- 2. **「冷冻速食」与「方便速食」都留，不合并。** 前者要冷链、后者常温，
--    履约要求不同：合成一类的话，买家会在同一个类目里看到需要冷链和不需要冷链的货，
--    而商家配送时无从区分。
--
-- 3. **卡券 / 虚拟商品 / 服饰鞋帽这三个一级目前整体停用**，子类目照样补。
--    尤其「卡券」下面此前**一个二级都没有** —— 运营真要开它的那天，
--    开出来是一棵空树，而那正是最容易被当成 bug 的形状。
--
-- 停用的类目不进规格覆盖率视图（`CategoryServiceImpl.tree()` 只返 ACTIVE），
-- 所以补这 15 条不会凭空多出 15 个「未配置」红标。
--
-- 门槛码全部复用现有且已启用的（FOOD / PACKAGED_FOOD / DAILY / HOUSEKEEPING），
-- 不新增码 —— 新码要配资质、要有人去审，而这 15 条现在一条都没开。


INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT145' AS category_no, 'CAT100' AS parent_no, 2 AS level, '豆制品' AS name, 'Bean Products' AS name_en,
 NULL AS icon, 45 AS sort, 'FRESH' AS template, NULL AS attr_template,
 '["食品经营许可证"]' AS qualification_required, 'FOOD' AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT145');

INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT185' AS category_no, 'CAT100' AS parent_no, 2 AS level, '预制半成品菜' AS name, 'Prepared Dishes' AS name_en,
 NULL AS icon, 85 AS sort, 'STANDARD' AS template, NULL AS attr_template,
 '["食品经营许可证"]' AS qualification_required, 'FOOD' AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT185');

INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT195' AS category_no, 'CAT100' AS parent_no, 2 AS level, '冷冻速食' AS name, 'Frozen Food' AS name_en,
 NULL AS icon, 95 AS sort, 'STANDARD' AS template, NULL AS attr_template,
 '["仅销售预包装食品备案"]' AS qualification_required, 'PACKAGED_FOOD' AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT195');

INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT295' AS category_no, 'CAT200' AS parent_no, 2 AS level, '洗护清洁' AS name, 'Cleaning & Care' AS name_en,
 NULL AS icon, 15 AS sort, 'STANDARD' AS template, NULL AS attr_template,
 NULL AS qualification_required, 'DAILY' AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT295');

INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT285' AS category_no, 'CAT200' AS parent_no, 2 AS level, '五金电料' AS name, 'Hardware & Electrical' AS name_en,
 NULL AS icon, 85 AS sort, 'STANDARD' AS template, NULL AS attr_template,
 NULL AS qualification_required, 'DAILY' AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT285');

INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT375' AS category_no, 'CAT300' AS parent_no, 2 AS level, '照护陪护' AS name, 'Caregiving' AS name_en,
 NULL AS icon, 75 AS sort, 'SERVICE' AS template, NULL AS attr_template,
 NULL AS qualification_required, 'HOUSEKEEPING' AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT375');

INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT385' AS category_no, 'CAT300' AS parent_no, 2 AS level, '搬家搬运' AS name, 'Moving & Hauling' AS name_en,
 NULL AS icon, 85 AS sort, 'SERVICE' AS template, NULL AS attr_template,
 NULL AS qualification_required, NULL AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT385');

INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT395' AS category_no, 'CAT300' AS parent_no, 2 AS level, '回收' AS name, 'Recycling' AS name_en,
 NULL AS icon, 95 AS sort, 'SERVICE' AS template, NULL AS attr_template,
 NULL AS qualification_required, NULL AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT395');

INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT760' AS category_no, 'CAT700' AS parent_no, 2 AS level, '方便速食' AS name, 'Instant Food' AS name_en,
 NULL AS icon, 60 AS sort, 'STANDARD' AS template, NULL AS attr_template,
 '["仅销售预包装食品备案"]' AS qualification_required, 'PACKAGED_FOOD' AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT760');

INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT410' AS category_no, 'CAT400' AS parent_no, 2 AS level, '服务次卡' AS name, 'Service Passes' AS name_en,
 NULL AS icon, 10 AS sort, 'VOUCHER' AS template, NULL AS attr_template,
 NULL AS qualification_required, NULL AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT410');

INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT420' AS category_no, 'CAT400' AS parent_no, 2 AS level, '代金券' AS name, 'Gift Vouchers' AS name_en,
 NULL AS icon, 20 AS sort, 'VOUCHER' AS template, NULL AS attr_template,
 NULL AS qualification_required, NULL AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT420');

INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT530' AS category_no, 'CAT500' AS parent_no, 2 AS level, '生活缴费' AS name, 'Utility Payments' AS name_en,
 NULL AS icon, 30 AS sort, 'VIRTUAL' AS template, NULL AS attr_template,
 NULL AS qualification_required, NULL AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT530');

INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT540' AS category_no, 'CAT500' AS parent_no, 2 AS level, '交通出行卡' AS name, 'Transit Cards' AS name_en,
 NULL AS icon, 40 AS sort, 'VIRTUAL' AS template, NULL AS attr_template,
 NULL AS qualification_required, NULL AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT540');

INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT940' AS category_no, 'CAT900' AS parent_no, 2 AS level, '成人服饰' AS name, 'Apparel' AS name_en,
 NULL AS icon, 40 AS sort, 'STANDARD' AS template, NULL AS attr_template,
 NULL AS qualification_required, NULL AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT940');

INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT950' AS category_no, 'CAT900' AS parent_no, 2 AS level, '童装' AS name, 'Kidswear' AS name_en,
 NULL AS icon, 50 AS sort, 'STANDARD' AS template, NULL AS attr_template,
 NULL AS qualification_required, NULL AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT950');
