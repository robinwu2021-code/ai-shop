-- 预包装食品合规组 + TEXT 自由文本维度（TDD-商品属性维度补全）。
--
-- 对标淘宝/拼多多，预包装食品详情页的「商品参数」至少要有：品牌、净含量、配料、
-- 厂名厂址、生产许可证(SC)、执行标准。本仓库此前一条都没有维度 ——
-- 建品页的参数区最多带出产地/保质期/储存，合规那几项商家无处可填。
--
-- ── 两种性质，两条路 ───────────────────────────────────────────────────────
-- · 品牌    → ENUM，入平台值池。长尾但**高度可聚合、买家要按它筛**，
--             池起步为空、商家填了即入池（与产地/口感同一条路）。universal：
--             品牌的含义跨类目一致（伊利在牛奶和在雪糕是同一个伊利）。
-- · 其余 5 项 → **TEXT（本迁移引入的新 value_type）**：每件商品几乎唯一，
--             入池只会堆满永不复用的唯一串，而养这个池的唯一理由是跨店聚合 ——
--             唯一串聚不起来。所以 TEXT 的值不进 prd_spec_value，
--             只作为快照落在 prd_goods.params[].label（保存链路本就按 label 存，
--             不校验 valueNo，这正是 TEXT 不入池能成立的原因）。
--
-- 净含量也走 TEXT 而非 QUANT：单位混杂（500g / 1.5L / 12 枚），
-- 强行 QUANT 要给每个值配归一量，而买家并不按净含量排序比价。

-- ── 维度 ───────────────────────────────────────────────────────────────────
-- TEXT 维度不配 unit、不配值（prd_spec_value 一行都不写）。
INSERT IGNORE INTO prd_spec_dim
  (dim_no, code, name, value_type, unit, usage_type, universal, scope, sort, status,
   tenant_no, created_at, created_by, updated_at, updated_by)
VALUES
  ('SD_BRAND',        'BRAND',        '品牌',          'ENUM', NULL, 'PROP', 1, 'PLATFORM', 50,  'ACTIVE',
   'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_NET_CONTENT',  'NET_CONTENT',  '净含量',        'TEXT', NULL, 'PROP', 0, 'PLATFORM', 240, 'ACTIVE',
   'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_INGREDIENTS',  'INGREDIENTS',  '配料',          'TEXT', NULL, 'PROP', 0, 'PLATFORM', 250, 'ACTIVE',
   'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_MANUFACTURER', 'MANUFACTURER', '厂名厂址',      'TEXT', NULL, 'PROP', 0, 'PLATFORM', 260, 'ACTIVE',
   'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_LICENSE_SC',   'LICENSE_SC',   '生产许可证(SC)', 'TEXT', NULL, 'PROP', 0, 'PLATFORM', 270, 'ACTIVE',
   'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_STANDARD',     'STANDARD',     '执行标准',      'TEXT', NULL, 'PROP', 0, 'PLATFORM', 280, 'ACTIVE',
   'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');

-- ── 类目绑定 ───────────────────────────────────────────────────────────────
-- 绑到预包装食品/饮料的 7 个二级类目：CAT130 预包装食品、CAT160 茶叶、
-- CAT710 粮油调味、CAT720 休闲零食、CAT730 饮料冲调、CAT740 烘焙面点、CAT750 婴幼儿食品。
-- 这 7 个类目本就各有恰好一个主维度（SpecLibraryCoverageTest 的闸门），
-- 这里一律 is_primary=0 追加 PROP，不碰主维度。
-- 逐条写死不 CROSS JOIN（生成器解不开 INSERT…SELECT）。required 一律 0：本版不校验必填。
-- 生鲜 CAT110/120 不在此列：配料/SC/执行标准对散装果蔬不成立，其维度 V349 已另绑。
INSERT IGNORE INTO prd_category_spec
  (category_no, dim_no, usage_type, is_primary, required, sort, status,
   tenant_no, created_at, created_by, updated_at, updated_by)
VALUES
  ('CAT130', 'SD_BRAND',        'PROP', 0, 0, 60,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_NET_CONTENT',  'PROP', 0, 0, 70,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_INGREDIENTS',  'PROP', 0, 0, 80,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_MANUFACTURER', 'PROP', 0, 0, 90,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_LICENSE_SC',   'PROP', 0, 0, 100, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_STANDARD',     'PROP', 0, 0, 110, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_BRAND',        'PROP', 0, 0, 60,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_NET_CONTENT',  'PROP', 0, 0, 70,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_INGREDIENTS',  'PROP', 0, 0, 80,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_MANUFACTURER', 'PROP', 0, 0, 90,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_LICENSE_SC',   'PROP', 0, 0, 100, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_STANDARD',     'PROP', 0, 0, 110, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_BRAND',        'PROP', 0, 0, 60,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_NET_CONTENT',  'PROP', 0, 0, 70,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_INGREDIENTS',  'PROP', 0, 0, 80,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_MANUFACTURER', 'PROP', 0, 0, 90,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_LICENSE_SC',   'PROP', 0, 0, 100, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_STANDARD',     'PROP', 0, 0, 110, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_BRAND',        'PROP', 0, 0, 60,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_NET_CONTENT',  'PROP', 0, 0, 70,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_INGREDIENTS',  'PROP', 0, 0, 80,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_MANUFACTURER', 'PROP', 0, 0, 90,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_LICENSE_SC',   'PROP', 0, 0, 100, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_STANDARD',     'PROP', 0, 0, 110, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_BRAND',        'PROP', 0, 0, 60,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_NET_CONTENT',  'PROP', 0, 0, 70,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_INGREDIENTS',  'PROP', 0, 0, 80,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_MANUFACTURER', 'PROP', 0, 0, 90,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_LICENSE_SC',   'PROP', 0, 0, 100, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_STANDARD',     'PROP', 0, 0, 110, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_BRAND',        'PROP', 0, 0, 60,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_NET_CONTENT',  'PROP', 0, 0, 70,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_INGREDIENTS',  'PROP', 0, 0, 80,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_MANUFACTURER', 'PROP', 0, 0, 90,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_LICENSE_SC',   'PROP', 0, 0, 100, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_STANDARD',     'PROP', 0, 0, 110, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_BRAND',        'PROP', 0, 0, 60,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_NET_CONTENT',  'PROP', 0, 0, 70,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_INGREDIENTS',  'PROP', 0, 0, 80,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_MANUFACTURER', 'PROP', 0, 0, 90,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_LICENSE_SC',   'PROP', 0, 0, 100, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_STANDARD',     'PROP', 0, 0, 110, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
