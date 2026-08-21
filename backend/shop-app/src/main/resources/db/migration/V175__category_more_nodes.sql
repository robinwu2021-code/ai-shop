-- 类目补齐：电子产品、酒类、茶叶。
--
-- 三条判据，与 V22 那批同一套：
--   ① **能在营业执照里指到一句对应表述**（酒类=含酒类的食品经营许可证；茶叶=预包装食品备案）；
--   ② **两级封顶**（V168 之后的模型）：新增只在一级/二级上做，不再出现三级；
--   ③ **模板决定形态**：这三类都是标品（STANDARD → NORMAL），不是生鲜 ——
--      形态错了不报错，只会让一件电视机在详情页上要求填截单时间。

-- ── 1. 酒类的授权码 ───────────────────────────────────────────
--
-- 单开一个码而不是复用 PACKAGED_FOOD：酒类零售要的是**含酒类经营范围**的
-- 食品经营许可证，与「仅销售预包装食品备案」不是同一张证。
-- 复用的后果是拿着备案的商家能上酒 —— 而那正是这道门槛存在的理由。
INSERT INTO sys_auth_code
(code, name, required_qualification, sort, enabled, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('ALCOHOL', '酒类', '食品经营许可证（含酒类）', 27, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);

-- ── 2. 食品生鲜下补两个二级：酒类、茶叶 ───────────────────────
--
-- 茶叶原本是三级（CAT133，V168 已归档）。这里**新建二级节点而不是复活它**：
-- 复活会让一个 level=3 的节点挂在两级模型里，而端上的选择器只渲染两层 ——
-- 它会变成一个查得到、选不到的类目。
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('CAT150', 'CAT100', 2, '酒类', 'Alcohol', NULL, 50, 'STANDARD', NULL,
 '["食品经营许可证（含酒类）"]', 'ALCOHOL', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT160', 'CAT100', 2, '茶叶', 'Tea', NULL, 60, 'STANDARD', NULL,
 '["仅销售预包装食品备案"]', 'PACKAGED_FOOD', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);

-- ── 3. 新的一级：电子产品 ─────────────────────────────────────
--
-- 放一级而不是塞进「日用百货」：日用百货的二级已经是纸品/家居/个护/医药四档，
-- 再塞进手机与家电，商家在选择器里要在一屏杂货里找电视机。
--
-- **无经营门槛**：3C 认证是**商品**的合规要求（由商品审核看），
-- 不是**主体**的经营资质。挂成 required_code 的话，
-- 会变成「有证才能卖任何电子产品」，而那张证根本不是发给店铺的。
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('CAT600', NULL,     1, '电子产品', 'Electronics',      NULL, 60, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT610', 'CAT600', 2, '手机数码', 'Phones & Digital', NULL, 10, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT620', 'CAT600', 2, '家用电器', 'Home Appliances',  NULL, 20, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT630', 'CAT600', 2, '配件耗材', 'Accessories',      NULL, 30, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
