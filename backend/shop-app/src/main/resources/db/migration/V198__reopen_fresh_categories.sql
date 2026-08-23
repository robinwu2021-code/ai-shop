-- 重开三个生鲜二级类目：肉禽蛋、乳制品、水产海鲜。
--
-- 线上「食品生鲜」下 9 个二级归档了 6 个，商家实际只能选蔬菜/水果/熟食卤味 ——
-- 而肉禽蛋与乳制品是社区店的日常主力，水产海鲜是周末的主力。三条门槛码
-- （FRESH_MEAT / FRESH_DAIRY / FRESH_AQUATIC）都已在 sys_auth_code 里，
-- 上架那一刻仍旧按资质卡，开类目不等于放行。
--
-- **酒类（CAT150）继续关着**：它要的是专门的食品经营许可证（含酒类），
-- 而平台这一期没有对应的资质审核链路。开一个审不了的类目，等于把拒绝推迟到上架那一刻。
-- 预包装食品（CAT130）与茶叶（CAT160）同理留待评审。
UPDATE prd_category SET status = 'ACTIVE', updated_at = NOW(), updated_by = 'SYSTEM'
 WHERE category_no IN ('CAT170', 'CAT180', 'CAT190');

-- 乳制品与水产海鲜的规格绑定（肉禽蛋已在 V196）。
-- 乳制品按容量卖（盒装奶/酸奶），水产按份量与处理方式 —— 与肉禽蛋同一套轴。
INSERT INTO prd_category_spec (category_no, dim_no, usage_type, is_primary, required, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('CAT180', 'SD_VOLUME', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_COUNT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_PACK', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_SHELF_LIFE', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_CUT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_PACK', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_ORIGIN', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');

INSERT INTO prd_category_spec_value (category_no, dim_no, value_no, label_override, sort, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  -- 乳制品：盒装奶常见的四档容量
  ('CAT180', 'SD_VOLUME', 'SV_VOLUME_V200ML', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_VOLUME', 'SV_VOLUME_V250ML', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_VOLUME', 'SV_VOLUME_V500ML', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_VOLUME', 'SV_VOLUME_V1L', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_COUNT', 'SV_COUNT_C1', '单盒', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_COUNT', 'SV_COUNT_C6', '6盒装', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_COUNT', 'SV_COUNT_C12', '12盒装', 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_COUNT', 'SV_COUNT_CCASE', '整箱', 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_PACK', 'SV_PACK_PBOX', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_PACK', 'SV_PACK_PBOTTLE', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_PACK', 'SV_PACK_PBAG', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  -- 乳制品的保质期短，只给近的三档：给到 24 个月会让人以为鲜奶能放两年
  ('CAT180', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF7D', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF1M', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF6M', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  -- 水产：与肉禽蛋一样按斤说话
  ('CAT190', 'SD_WEIGHT', 'SV_WEIGHT_W250G', '半斤', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_WEIGHT', 'SV_WEIGHT_W500G', '1斤', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_WEIGHT', 'SV_WEIGHT_W1KG', '2斤', 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_WEIGHT', 'SV_WEIGHT_W2KG', '4斤', 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_CUT', 'SV_CUT_CUTWHOLE', '整条', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_CUT', 'SV_CUT_CUTCHUNK', '切段', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_CUT', 'SV_CUT_CUTSLICE', '切片', 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_CUT', 'SV_CUT_CUTBONE', '去骨', 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_PACK', 'SV_PACK_PBULK', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_PACK', 'SV_PACK_PBOX', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_PACK', 'SV_PACK_PVACUUM', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
