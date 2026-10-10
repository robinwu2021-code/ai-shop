-- 给 V216 那 15 个类目配默认规格，并补两个**卡券与服务离不开的维度**。
--
-- 承 V216：那 15 个类目建好了但没有规格绑定。类目没有绑定 = 商家选完类目，
-- 规格区一片空白 —— 而「这袋货按什么分规格」恰恰是建品最难的一步。
-- 一起备好，运营开一个类目就是能用的，不必再去规格库里配一遍。
--
-- ── 为什么要新增两个维度 ──
--
-- 面值（元）与次数（次）在库里**此前一个都没有**，而卡券、代金券、生活缴费、
-- 交通卡、服务次卡这五类离了它们无法表达 —— 「50元」只能被商家打成自由文本，
-- 于是同一张 50 元券在三家店里是三个不相干的字符串，比价与聚合当场断掉。
--
-- 两个都是 QUANT（带归一量），理由与重量/容量一致：**面值要能排序与比较**。
-- 都标 universal=0（专用）：它们只在卡券/虚拟/服务这几类成立，
-- 标成通用会让它们出现在蔬菜的候选里 —— 那正是「数码里能挑口味」的同一个毛病。
--
-- ── 主维度怎么定 ──
--
-- 每类恰好一个（守卫 SpecLibraryCoverageTest 测着）。判据是**买家最先问的那件事**：
-- 豆制品问多重、洗护清洁问多大瓶、搬家问多远、代金券问多少钱、次卡问几次。
-- 不是「最常用的属性」—— 颜色在服饰里很常用，但买家先问的是尺码。


-- ── 1. 两个新维度 ──
INSERT INTO prd_spec_dim (dim_no, code, name, value_type, unit, usage_type, universal, scope, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('SD_FACE_VALUE', 'FACE_VALUE', '面值', 'QUANT', '元', 'SALE', 0, 'PLATFORM', 280, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_TIMES', 'TIMES', '次数', 'QUANT', '次', 'SALE', 0, 'PLATFORM', 290, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');

-- ── 2. 它们的取值（带归一量，才能排序与比价）──
INSERT INTO prd_spec_value (value_no, dim_no, code, label, numeric_value, numeric_unit, aliases, scope, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('SV_FACE_VALUE_F10', 'SD_FACE_VALUE', 'F10', '10元', 10, '元', NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FACE_VALUE_F20', 'SD_FACE_VALUE', 'F20', '20元', 20, '元', NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FACE_VALUE_F30', 'SD_FACE_VALUE', 'F30', '30元', 30, '元', NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FACE_VALUE_F50', 'SD_FACE_VALUE', 'F50', '50元', 50, '元', NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FACE_VALUE_F100', 'SD_FACE_VALUE', 'F100', '100元', 100, '元', NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FACE_VALUE_F200', 'SD_FACE_VALUE', 'F200', '200元', 200, '元', NULL, 'PLATFORM', 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FACE_VALUE_F500', 'SD_FACE_VALUE', 'F500', '500元', 500, '元', NULL, 'PLATFORM', 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FACE_VALUE_F1000', 'SD_FACE_VALUE', 'F1000', '1000元', 1000, '元', NULL, 'PLATFORM', 80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_TIMES_T1', 'SD_TIMES', 'T1', '1次', 1, '次', NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_TIMES_T3', 'SD_TIMES', 'T3', '3次', 3, '次', NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_TIMES_T5', 'SD_TIMES', 'T5', '5次', 5, '次', NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_TIMES_T10', 'SD_TIMES', 'T10', '10次', 10, '次', NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_TIMES_T20', 'SD_TIMES', 'T20', '20次', 20, '次', NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_TIMES_T30', 'SD_TIMES', 'T30', '30次', 30, '次', NULL, 'PLATFORM', 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');

-- ── 3. 15 个类目的默认规格 ──
INSERT INTO prd_category_spec (category_no, dim_no, usage_type, is_primary, required, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('CAT145', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT145', 'SD_PACK', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT185', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT185', 'SD_FLAVOR', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT185', 'SD_SHELF_LIFE', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT195', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT195', 'SD_FLAVOR', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT195', 'SD_PACK', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT295', 'SD_VOLUME', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT295', 'SD_PACK', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT295', 'SD_COUNT', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT285', 'SD_COUNT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT285', 'SD_SIZE_GRADE', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT285', 'SD_MATERIAL', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT375', 'SD_DURATION', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT375', 'SD_HEADCOUNT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT385', 'SD_DISTANCE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT385', 'SD_ROOM', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT385', 'SD_HEADCOUNT', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT395', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT395', 'SD_DISTANCE', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT760', 'SD_FLAVOR', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT760', 'SD_COUNT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT760', 'SD_WEIGHT', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT410', 'SD_TIMES', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT410', 'SD_DURATION', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT420', 'SD_FACE_VALUE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT530', 'SD_FACE_VALUE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT540', 'SD_FACE_VALUE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT940', 'SD_SIZE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT940', 'SD_COLOR', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT950', 'SD_SIZE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT950', 'SD_COLOR', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT950', 'SD_AGE', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
