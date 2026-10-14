-- 品类由类目派生（商品域优化清单 P1-1）。
--
-- 背景：系统里有两套分类，`prd_goods.type`（五品类，硬编码，管履约与合规）与
-- `prd_category`（三级树，运营维护，管归类与准入）。两者不是重复：前者是代码分支，
-- 后者是数据。但它们此前是**两个输入点** —— 商家在建品页把同一件事填两遍，
-- 而且允许矛盾（选「叶菜」类目配 NORMAL 品类，没有一处会拦，直到下单时因为
-- 履约方式不对才出问题）。
--
-- 本迁移把「类目」变成唯一输入，为后端按 `prd_category.template` 派生 type 铺路：
--   1. 补上 VIRTUAL 这一支 —— 没有它，品类一旦改成派生，虚拟商品就再也建不出来
--   2. 回填存量商品的 category_no —— 留着空值，「品类由类目推出」就有例外
--   3. 把存量矛盾（类目说生鲜、type 写 NORMAL）刷齐
--
-- 命名收敛（STANDARD↔NORMAL、VOUCHER↔CARD 两套码）**不在本迁移内**：
-- 它要同时动 ops-web 的 mock 与守卫，blast radius 与本迁移不是一个量级，
-- 单独一轮做。在那之前两套码之间靠 `CategoryTemplates` 那张映射表挡着。

-- ── 1. VIRTUAL 分支 ────────────────────────────────────────────────
--
-- 与卡券分开：卡券要到店核销（STORE_VERIFY），虚拟商品是即时发放（INSTANT），
-- 两者履约方式不同 —— 这正是 TEMPLATE_TO_TYPE 里 VOUCHER→CARD 而不是 →VIRTUAL 的理由。
-- 编号延续 CAT1xx 的段位约定，虚拟占 CAT5xx。
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('CAT500', NULL,     1, '虚拟商品', 'Virtual Goods', NULL, 50, 'VIRTUAL', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT510', 'CAT500', 2, '话费充值', 'Mobile Top-up', NULL, 10, 'VIRTUAL', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT520', 'CAT500', 2, '会员充值', 'Memberships',   NULL, 20, 'VIRTUAL', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);

-- ── 2. 存量商品回填 category_no ────────────────────────────────────
--
-- 一律回填到**一级类目**，不落到叶子：叶子上挂着 required_code（叶菜要
-- 食品经营许可证），回填到那儿会让一批本来在架的存量商品突然上不了架 ——
-- 而商家什么都没做过。一级类目无门槛，回填是「补上归类」而不是「新增一道闸」。
UPDATE prd_goods SET category_no = 'CAT100' WHERE (category_no IS NULL OR category_no = '') AND type = 'FRESH';
UPDATE prd_goods SET category_no = 'CAT300' WHERE (category_no IS NULL OR category_no = '') AND type = 'SERVICE';
UPDATE prd_goods SET category_no = 'CAT400' WHERE (category_no IS NULL OR category_no = '') AND type = 'CARD';
UPDATE prd_goods SET category_no = 'CAT500' WHERE (category_no IS NULL OR category_no = '') AND type = 'VIRTUAL';
-- 兜底：NORMAL 与任何认不出的 type 都归日用百货。空串一并收掉 ——
-- category_no='' 的商品既不出现在任何类目筛选里，也不为空，查起来看不出哪里不对
UPDATE prd_goods SET category_no = 'CAT200' WHERE category_no IS NULL OR category_no = '';

-- ── 3. 把存量矛盾刷齐 ──────────────────────────────────────────────
--
-- 只覆盖种子类目（CAT1xx–CAT5xx）：它们是当前树的全部。运营后续新建的类目
-- 由后端派生保证一致（新建/编辑商品时 type 直接由 template 算出，写不进矛盾值），
-- 不需要也不该在迁移里追。
--
-- 刻意不用 UPDATE ... JOIN：MariaDB 支持而 H2（集成测试用）不支持，
-- 而「测试绿 ≠ 生产对」在这个仓库里是有案底的。显式列表两边语义相同。
UPDATE prd_goods SET type = 'FRESH'   WHERE category_no IN ('CAT100','CAT110','CAT120','CAT111','CAT112','CAT121') AND type <> 'FRESH';
UPDATE prd_goods SET type = 'NORMAL'  WHERE category_no IN ('CAT200','CAT210') AND type <> 'NORMAL';
UPDATE prd_goods SET type = 'SERVICE' WHERE category_no IN ('CAT300') AND type <> 'SERVICE';
UPDATE prd_goods SET type = 'CARD'    WHERE category_no IN ('CAT400') AND type <> 'CARD';
UPDATE prd_goods SET type = 'VIRTUAL' WHERE category_no IN ('CAT500','CAT510','CAT520') AND type <> 'VIRTUAL';
