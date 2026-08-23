-- 补两个类目的规格：医药健康（CAT240）与维修安装（CAT320）。
--
-- 这两条是**上线当天被运营端那张「类目 × 规格」表当场揪出来的**：V196 的清单按本地
-- 类目树整理，而线上多开了这两个类目 —— 部署后表上立刻显示「28 个在售类目，26 个已配，
-- 2 个还空着」，两行标红。这正是那张表存在的理由：覆盖率是运行时事实，CI 猜不到它。

INSERT INTO prd_category_spec (category_no, dim_no, usage_type, is_primary, required, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('CAT240', 'SD_COUNT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_WEIGHT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_AGE', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_SHELF_LIFE', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_DURATION', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_HEADCOUNT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_DISTANCE', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');

-- 医药健康按「盒」卖，所以 COUNT 在这一类目下换个说法；净含量给保健品那半边用
INSERT INTO prd_category_spec_value (category_no, dim_no, value_no, label_override, sort, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('CAT240', 'SD_COUNT', 'SV_COUNT_C1', '单盒', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_COUNT', 'SV_COUNT_C2', '2盒装', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_COUNT', 'SV_COUNT_C3', '3盒装', 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_COUNT', 'SV_COUNT_CSET', '套装', 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_WEIGHT', 'SV_WEIGHT_W50G', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_WEIGHT', 'SV_WEIGHT_W100G', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_WEIGHT', 'SV_WEIGHT_W250G', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_AGE', 'SV_AGE_AGE03', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_AGE', 'SV_AGE_AGE12', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_AGE', 'SV_AGE_AGEADULT', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF12M', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF18M', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF24M', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  -- 维修安装按工时报价：时长是主维度，上门距离与人数是加价项
  ('CAT320', 'SD_DURATION', 'SV_DURATION_D30', '30分钟内', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_DURATION', 'SV_DURATION_D60', '1小时', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_DURATION', 'SV_DURATION_D120', '2小时', 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_HEADCOUNT', 'SV_HEADCOUNT_H1', '1人上门', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_HEADCOUNT', 'SV_HEADCOUNT_H2', '2人上门', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_DISTANCE', 'SV_DISTANCE_DIST3', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_DISTANCE', 'SV_DISTANCE_DIST5', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_DISTANCE', 'SV_DISTANCE_DIST10', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
