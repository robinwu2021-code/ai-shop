-- 生鲜补两个维度：品种、发货地（TDD-生鲜参数补品种与发货地 AC1/AC2）。
--
-- 用户 2026-10-07 提了四项：产地到县市 / 包装 / 水果品种 / 发货地。
-- 查过现状，**两项已经有了**，这里只补确实没有的那两个：
--   · 包装   SD_PACK          已存在（袋装/盒装/散装/礼盒装等 8 个值），且已绑 CAT120
--   · 产地   SD_ORIGIN_DETAIL 已存在（TEXT，V375），已绑生鲜；「到县市」本版靠录入保证
--
-- 为什么两个都是 TEXT 而不是 ENUM —— 与 V375 逐字同一条理由：
-- 柿子有阳丰/富有/次郎，苹果有红富士/嘎啦，葡萄有阳光玫瑰/巨峰。要穷举值集就得按
-- **三级类目**各配一套，而绑定主力 CAT120 是二级；硬入 prd_spec_value 只会堆满
-- 永不复用的唯一串，聚合不起来（与配料、原产地同一个坑）。
-- 值作快照落 prd_goods.params[].label。将来真要按品种筛选，再加值集回填即可 ——
-- TEXT 升 ENUM 是加东西，不是破坏性变更。
--
-- 发货地与产地是**两件事**：产地说这批货长在哪儿，发货地说包裹从哪儿寄出。
-- 跨省代发时两者不同，而买家据以判断要等几天。
--
-- universal=0：这两个都是生鲜语境的字段，不该摆进所有类目的「添加参数」面板。

INSERT IGNORE INTO prd_spec_dim
  (dim_no, code, name, value_type, unit, usage_type, universal, scope, sort, status,
   tenant_no, created_at, created_by, updated_at, updated_by)
VALUES
  ('SD_VARIETY', 'VARIETY', '品种', 'TEXT', NULL, 'PROP', 0, 'PLATFORM', 206, 'ACTIVE',
   'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_SHIP_FROM', 'SHIP_FROM', '发货地', 'TEXT', NULL, 'PROP', 0, 'PLATFORM', 207, 'ACTIVE',
   'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');

-- 绑生鲜四个类目：CAT110 蔬菜 / CAT120 水果 / CAT121 浆果 / CAT122 常温水果。
-- 这些类目各已有恰好一个主维度（SpecLibraryCoverageTest 的闸门），这里一律
-- is_primary=0 追加 PROP，不动它们原有的主维度。
--
-- sort 206/207：紧跟 SD_ORIGIN_DETAIL(205)，于是参数区里
-- 产地 → 原产地 → 品种 → 发货地 连着出现，而不是散在保质期两侧。
--
-- 发货地对所有快递商品都有意义，但本版**只绑生鲜四个**：先在一个类目族里验，
-- 别一次铺满 48 个类目（铺开之后再想收就要动存量商品的参数了）。
INSERT IGNORE INTO prd_category_spec
  (category_no, dim_no, usage_type, is_primary, required, sort, status,
   tenant_no, created_at, created_by, updated_at, updated_by)
VALUES
  ('CAT110', 'SD_VARIETY', 'PROP', 0, 0, 206, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_VARIETY', 'PROP', 0, 0, 206, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT121', 'SD_VARIETY', 'PROP', 0, 0, 206, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT122', 'SD_VARIETY', 'PROP', 0, 0, 206, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT110', 'SD_SHIP_FROM', 'PROP', 0, 0, 207, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_SHIP_FROM', 'PROP', 0, 0, 207, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT121', 'SD_SHIP_FROM', 'PROP', 0, 0, 207, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT122', 'SD_SHIP_FROM', 'PROP', 0, 0, 207, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
