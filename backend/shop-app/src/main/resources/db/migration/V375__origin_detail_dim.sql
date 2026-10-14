-- 精确产地（原产地）维度（TDD-商品录入优化5项 AC3）。
--
-- 现有 SD_ORIGIN 是粗粒度 ENUM（本地/国产/进口）——买家筛选、跨店聚合靠它。
-- 但买菜/买食品的人还想看「云南 昭通 鲁甸」这种省市区级产地,而那几乎每件唯一:
-- 入 prd_spec_value 只会堆满永不复用的唯一串,聚合不起来（与配料同一个坑）。
-- 所以精确产地走 **TEXT 维度(不入池)**,值作快照落 prd_goods.params[].label。
-- 两层并存:SD_ORIGIN 粗筛 + SD_ORIGIN_DETAIL 精确展示。
--
-- universal=0:原产地是食品/生鲜语境的字段,不该摆到所有类目的「添加参数」面板里。

INSERT IGNORE INTO prd_spec_dim
  (dim_no, code, name, value_type, unit, usage_type, universal, scope, sort, status,
   tenant_no, created_at, created_by, updated_at, updated_by)
VALUES
  ('SD_ORIGIN_DETAIL', 'ORIGIN_DETAIL', '原产地', 'TEXT', NULL, 'PROP', 0, 'PLATFORM', 205, 'ACTIVE',
   'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');

-- 绑生鲜(CAT110 蔬菜 / CAT120 水果 / CAT121 / CAT122)+ 预包装食品饮料 7 个二级类目。
-- 这些类目都已各有恰好一个主维度(SpecLibraryCoverageTest 的闸门),这里一律 is_primary=0 追加 PROP。
-- sort=205:排在 SD_ORIGIN(200)之后、保质期(210)之前,精确产地紧跟粗产地。
INSERT IGNORE INTO prd_category_spec
  (category_no, dim_no, usage_type, is_primary, required, sort, status,
   tenant_no, created_at, created_by, updated_at, updated_by)
VALUES
  ('CAT110', 'SD_ORIGIN_DETAIL', 'PROP', 0, 0, 205, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_ORIGIN_DETAIL', 'PROP', 0, 0, 205, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT121', 'SD_ORIGIN_DETAIL', 'PROP', 0, 0, 205, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT122', 'SD_ORIGIN_DETAIL', 'PROP', 0, 0, 205, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_ORIGIN_DETAIL', 'PROP', 0, 0, 205, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_ORIGIN_DETAIL', 'PROP', 0, 0, 205, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_ORIGIN_DETAIL', 'PROP', 0, 0, 205, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_ORIGIN_DETAIL', 'PROP', 0, 0, 205, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_ORIGIN_DETAIL', 'PROP', 0, 0, 205, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_ORIGIN_DETAIL', 'PROP', 0, 0, 205, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_ORIGIN_DETAIL', 'PROP', 0, 0, 205, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
