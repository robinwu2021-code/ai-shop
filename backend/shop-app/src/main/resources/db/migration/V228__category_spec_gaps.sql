-- 按线上真实商品补类目缺的维度与档位。
--
-- 来路：把线上 198 件商品的规格文案逐条拿去规格库里找归宿，找不到的按类目摊开看。
-- 出来的缺口非常具体，而且**大多不是库里没有这个说法，是这个类目没绑那个维度**：
--
--   纸品清洁卖「10斤装」「500g」「1.5kg」，而它绑的是 数量/容量/包装/材质 —— 没有重量；
--   熟食卤味卖「6只」「整只」「2份」，而它绑的是 重量/口味/包装/保质期/尺码/人数 —— 没有数量、没有处理方式；
--   家政上门卖「单台」，保洁卖「单只」「单件」，两边都没绑数量。
--
-- 这类缺口的代价不是「少一个选项」：类目一个维度都对不上时，建品页会掉回
-- 老模板的品类兜底，于是商家看到的组名就叫「规格」，存进去的规格组没有 templateNo，
-- 后面整条归一链路（值编号、跨店比价）全部落空。线上 198 件里 197 件就是这么来的。
--
-- **只补活着的类目**。停用类目下还压着 69 件商品（卡券 32、粮油调味 12、常温水果 12、
-- 休闲零食 10、茶叶 3），它们该迁走还是该下架是运营的判断，不在这条迁移里替他们定。

-- ── 1. 补档位（都是线上真实出现过、而库里没有的）──
INSERT INTO prd_spec_value (value_no, dim_no, code, label, numeric_value, numeric_unit, aliases, scope, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  -- 熟食：库里有「整只」没有「半只」，而半只鸡是熟食摊的常规卖法
  ('SV_CUT_CUTHALF', 'SD_CUT', 'CUTHALF', '半只', NULL, NULL, NULL, 'PLATFORM', 15, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  -- 3kg 顺带把「6斤」收进来（6 斤 = 3000 克，两种说法同一档）
  ('SV_WEIGHT_W3KG', 'SD_WEIGHT', 'W3KG', '3kg', 3000, 'g', '["6斤", "6斤装", "3千克"]', 'PLATFORM', 3000, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  -- 医药健康的口服液、外用液常见这两档
  ('SV_VOLUME_V30ML', 'SD_VOLUME', 'V30ML', '30ml', 30, 'ml', NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V60ML', 'SD_VOLUME', 'V60ML', '60ml', 60, 'ml', NULL, 'PLATFORM', 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V400ML', 'SD_VOLUME', 'V400ML', '400ml', 400, 'ml', NULL, 'PLATFORM', 400, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  -- 时长维度的单位是分钟，而家政按小时报价 —— 存归一量、显示挂别名，两边都不将就
  ('SV_DURATION_D240', 'SD_DURATION', 'D240', '240分钟', 240, '分钟', '["4小时"]', 'PLATFORM', 240, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');

-- 「单次」是「1次」最常见的说法，服务类商品几乎都这么写
UPDATE prd_spec_value SET aliases = '["单次", "一次"]', updated_at = NOW(), updated_by = 'SYSTEM'
WHERE value_no = 'SV_TIMES_T1' AND aliases IS NULL;

-- ── 2. 给活着的类目补维度 ──
--
-- 一律 is_primary=0：这些类目都已经有主规格了，主规格是「默认预填哪一条」，
-- 换掉它会改变商家建品时看到的第一屏，不该由一条补漏的迁移顺手决定。
INSERT INTO prd_category_spec (category_no, dim_no, usage_type, is_primary, required, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('CAT110', 'SD_COUNT', NULL, 0, 0, 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),  -- 蔬菜：3颗、2个
  ('CAT140', 'SD_COUNT', NULL, 0, 0, 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),  -- 熟食：6只、2份
  ('CAT140', 'SD_CUT',   NULL, 0, 0, 80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),  -- 熟食：整只、半只
  ('CAT210', 'SD_WEIGHT',NULL, 0, 0, 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),  -- 纸品清洁：洗衣液按斤/按克
  ('CAT210', 'SD_SIZE',  NULL, 0, 0, 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),  -- 纸品清洁：手套 S/M
  ('CAT240', 'SD_SIZE',  NULL, 0, 0, 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),  -- 医药健康：均码
  ('CAT310', 'SD_COUNT', NULL, 0, 0, 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),  -- 家政上门：单台
  ('CAT320', 'SD_COUNT', NULL, 0, 0, 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');  -- 保洁：单只、单件

-- ── 没做的，留个记号 ──
--
-- 服务类还有一批**计价单位**式的写法：100㎡内、每㎡、每米、每窗、3人位、1.8m、单把。
-- 它们不是「规格」而是「按什么计价」，硬塞进现有维度会把语义弄脏 ——
-- 该不该为它开一个「计价单位」维度，是产品决定，不是补漏能顺手做的。
-- 同理还有「月卡」「券包」（卡券类，本就该有自己的一套）与「大份」（份量，与尺码不是一回事）。
