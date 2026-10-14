-- 生鲜这一支的商品参数模板（TDD-C 端商品详情页·内容丰富度 §2.C）。
--
-- 详情页的「商品参数」区读 prd_goods.params，而商家能填哪几项由类目绑的 PROP 维度决定。
-- 线上在售的三件货都在 CAT120（水果），而那个类目**只绑了一个 PROP 维度**（产地）——
-- 于是参数区最多出一行，再怎么改版面也还是一行。
--
-- 这里补的是买菜的人真的会看的那几条，不是把维度表塞满：
--   · 保质期    SD_SHELF_LIFE  已有维度、已有值，只是从没绑到生鲜上
--   · 储存条件  SD_STORE_COND  新增。生鲜的第一问就是「要不要放冰箱」
--
-- **编号不叫 SD_STORAGE**：那个号已经被「存储」占着（手机内存 64G/128G，且是 SALE 维度）。
-- 第一版就是那么写的，H2 上 uk_spec_dim_no 直接撞 —— 而我当时只查了 PROP 维度，
-- 看不见一个 SALE 维度正用着这个号。
--   · 口感风味  SD_TASTE       新增。水果的选购理由，标品的「参数」在这里对应的就是它
-- 产地已绑在 CAT120 上，这里只补到蔬菜与两个三级类目。
--
-- **都是 PROP，不是 SALE**：SALE 会进 SKU 笛卡尔积，「冷藏 × 清甜 × 本地」会变成
-- 一个要单独定价备货的行（V250 的注释写着这条取舍）。

-- ── 维度 ───────────────────────────────────────────────────────────────────
INSERT IGNORE INTO prd_spec_dim
  (dim_no, code, name, value_type, unit, usage_type, universal, scope, sort, status,
   tenant_no, created_at, created_by, updated_at, updated_by)
VALUES
  ('SD_STORE_COND', 'STORE_COND', '储存条件', 'ENUM', NULL, 'PROP', 1, 'PLATFORM', 180, 'ACTIVE',
   'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_TASTE', 'TASTE', '口感风味', 'ENUM', NULL, 'PROP', 1, 'PLATFORM', 190, 'ACTIVE',
   'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');

-- ── 值 ─────────────────────────────────────────────────────────────────────
-- 值编号照 V196 的拼法：SV_<维度 code>_<值 code>。
INSERT IGNORE INTO prd_spec_value
  (value_no, dim_no, code, label, numeric_value, numeric_unit, aliases, scope, sort, status,
   tenant_no, created_at, created_by, updated_at, updated_by)
VALUES
  ('SV_STORE_COND_STGROOM', 'SD_STORE_COND', 'STGROOM', '常温', NULL, NULL, NULL, 'PLATFORM', 10,
   'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_STORE_COND_STGCOOL', 'SD_STORE_COND', 'STGCOOL', '阴凉干燥', NULL, NULL, NULL, 'PLATFORM', 20,
   'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_STORE_COND_STGCHILL', 'SD_STORE_COND', 'STGCHILL', '冷藏 0~5℃', NULL, NULL, NULL, 'PLATFORM', 30,
   'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_STORE_COND_STGFROZEN', 'SD_STORE_COND', 'STGFROZEN', '冷冻 -18℃', NULL, NULL, NULL, 'PLATFORM', 40,
   'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_TASTE_TSTSWEET', 'SD_TASTE', 'TSTSWEET', '清甜', NULL, NULL, NULL, 'PLATFORM', 10,
   'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_TASTE_TSTSOURSWT', 'SD_TASTE', 'TSTSOURSWT', '酸甜', NULL, NULL, NULL, 'PLATFORM', 20,
   'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_TASTE_TSTCRISP', 'SD_TASTE', 'TSTCRISP', '脆爽', NULL, NULL, NULL, 'PLATFORM', 30,
   'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_TASTE_TSTJUICY', 'SD_TASTE', 'TSTJUICY', '多汁', NULL, NULL, NULL, 'PLATFORM', 40,
   'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_TASTE_TSTSOFT', 'SD_TASTE', 'TSTSOFT', '软糯', NULL, NULL, NULL, 'PLATFORM', 50,
   'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_TASTE_TSTFRESH', 'SD_TASTE', 'TSTFRESH', '鲜嫩', NULL, NULL, NULL, 'PLATFORM', 60,
   'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');

-- ── 类目绑定 ───────────────────────────────────────────────────────────────
-- 逐条写死而不是 CROSS JOIN 一个类目列表：生成器解不开 INSERT…SELECT
-- （生成器的盲点那条教训），而这张表要进「类目×维度」的产物。
--
-- **已经绑过的不许再写一遍**（CAT110 的产地与保质期早就绑了）：`INSERT IGNORE` 在真库上
-- 确实幂等，但 gen-test-schema.py 会把 IGNORE 脱掉再落进 H2 的 schema-test.sql
-- （它自己的注释里写着这条），于是重复行在**测试**里撞唯一键，把整个 ApplicationContext
-- 带崩 —— 44 个用例一起 error，报错指向 bizAuthController，与这里毫无关系。
-- required 一律 0：本版不校验（prd_category_spec.required 的列注释写着），
-- 现在就置 1 等于给商家一个填不完的必填项，而老商品一个都没填。
INSERT IGNORE INTO prd_category_spec
  (category_no, dim_no, usage_type, is_primary, required, sort, status,
   tenant_no, created_at, created_by, updated_at, updated_by)
VALUES
  ('CAT110', 'SD_STORE_COND', 'PROP', 0, 0, 220, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT110', 'SD_TASTE', 'PROP', 0, 0, 230, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_SHELF_LIFE', 'PROP', 0, 0, 210, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_STORE_COND', 'PROP', 0, 0, 220, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_TASTE', 'PROP', 0, 0, 230, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  -- CAT121/CAT122 此前**一个维度都没绑**（线上查过）：商家选了三级类目就什么模板都没有。
  -- 顺手补上与 CAT120 同形的主维度 —— 「主维度恰好一个」是 SpecLibraryCoverageTest 的闸门，
  -- 只加 PROP 不加主维度的话，这两个类目会从「没人管」变成「绑了但没有主维度」，闸门当场红。
  ('CAT121', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT122', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT121', 'SD_ORIGIN', 'PROP', 0, 0, 200, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT121', 'SD_SHELF_LIFE', 'PROP', 0, 0, 210, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT121', 'SD_STORE_COND', 'PROP', 0, 0, 220, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT121', 'SD_TASTE', 'PROP', 0, 0, 230, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT122', 'SD_ORIGIN', 'PROP', 0, 0, 200, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT122', 'SD_SHELF_LIFE', 'PROP', 0, 0, 210, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT122', 'SD_STORE_COND', 'PROP', 0, 0, 220, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT122', 'SD_TASTE', 'PROP', 0, 0, 230, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
