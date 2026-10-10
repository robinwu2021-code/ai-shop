-- 水果的三个重量参数(TDD-商品快速录入-品类感知与逐项确认 §7 第 2 条)。
--
-- 用户的原话:水果有单个的克重、整体净重、毛重 —— 是三样东西。
-- 此前水果类目一个重量参数都没绑:没有单果重量、没有毛重,连已有的「净含量」都没绑到水果上。
-- 于是「单果140g+ 净重4.5斤」识别得再准也无处可落,只能落成维度号为中文名的游离参数
-- (2026-10-07 在生产草稿里实测到 dimNo 为「单果重量」「净重」的两条)。
--
-- 三个都走 TEXT(不入池,值作快照落 prd_goods.params[].label),与净含量 / 原产地同一口径:
--   · 「140g+」「约 200 克」带着量词与限定词,拆成数值会丢掉「+」「约」
--   · 每件的值几乎唯一,入 prd_spec_value 只会堆满永不复用的串
--
-- 毛重是商品级参数(真源),SKU 的标称重量(运费用)可按规格档从它带出默认值 —— 那是另一步。
--
-- universal=0:这几个是生鲜语境的字段,不该摆到所有类目的「添加参数」面板里。
-- sort 180/185/190:排在原产地(205)之前 —— 水果最先被问到的就是多重。

INSERT IGNORE INTO prd_spec_dim
  (dim_no, code, name, value_type, unit, usage_type, universal, scope, sort, status,
   tenant_no, created_at, created_by, updated_at, updated_by)
VALUES
  ('SD_UNIT_WEIGHT',  'UNIT_WEIGHT',  '单果重量', 'TEXT', NULL, 'PROP', 0, 'PLATFORM', 180, 'ACTIVE',
   'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_GROSS_WEIGHT', 'GROSS_WEIGHT', '毛重',     'TEXT', NULL, 'PROP', 0, 'PLATFORM', 190, 'ACTIVE',
   'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');

-- 绑水果:CAT120 水果 / CAT121 浆果 / CAT122 常温水果。净含量 SD_NET_CONTENT 是已有维度,这里只补绑定。
-- 一律 is_primary=0 追加 PROP —— 这些类目已各有恰好一个主维度(SpecLibraryCoverageTest 的闸门)。
INSERT IGNORE INTO prd_category_spec
  (category_no, dim_no, usage_type, is_primary, required, sort, status,
   tenant_no, created_at, created_by, updated_at, updated_by)
VALUES
  ('CAT120', 'SD_UNIT_WEIGHT',  'PROP', 0, 0, 180, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_NET_CONTENT',  'PROP', 0, 0, 185, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_GROSS_WEIGHT', 'PROP', 0, 0, 190, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT121', 'SD_UNIT_WEIGHT',  'PROP', 0, 0, 180, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT121', 'SD_NET_CONTENT',  'PROP', 0, 0, 185, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT121', 'SD_GROSS_WEIGHT', 'PROP', 0, 0, 190, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT122', 'SD_UNIT_WEIGHT',  'PROP', 0, 0, 180, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT122', 'SD_NET_CONTENT',  'PROP', 0, 0, 185, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT122', 'SD_GROSS_WEIGHT', 'PROP', 0, 0, 190, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
