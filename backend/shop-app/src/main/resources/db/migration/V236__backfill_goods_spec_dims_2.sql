-- 第二轮回填：承 V229 与 V235。
--
-- V229 那轮回填了 105 件，剩 92 件。这一轮把其中**启用类目下的 23 件**又拿下 22 件 ——
-- 靠的不是更聪明的猜法，而是 V235 补齐了它们缺的那一环。逐条查下来，
-- 绝大多数根本不是「库里没有这个说法」，而是**这个类目没绑那个维度**：
--
--   CAT310/CAT320 卖「单次」，而次数维度没绑（「1次」这一档早就在库里）
--   CAT240 卖「30ml / 60ml」、CAT140 卖「现磨豆浆 1L」，而容量维度都没绑
--   CAT220 卖「炒锅 32cm」，而口径维度没绑（顺带补了 32cm 那一档，库里只到 30cm）
--   熟食按「碗」「盅」卖，计件维度的量词别名里没有这两个字
--
-- 判据与 V229 逐字相同：**只有恰好一个维度能解释这一组的全部取值时才回填**，
-- 歧义或无解一律跳过。每条 UPDATE 都带原文精确匹配的闸门，商家若在这中间改过商品，
-- 条件不成立、这一条就不生效。SKU 那批只在值编号仍为空时才写，重复执行幂等。
--
-- 组名一并改正（只改恰好等于「规格」的那些）：面积 6、数量 6、次数 4、容量 3、长度 2、口径 1。
--
-- **剩下 1 件仍然回填不了**：卤味拼盘「大份」。它说的是**份量**，
-- 与 SD_SIZE_GRADE 的小号/中号/大号语义相近但不是一回事（那是器物大小，这是给多少）。
-- 一件商品不值得开一个维度，但熟食、快餐铺开之后它会成批出现 —— 那时再开。
--
-- 另有 69 件压在停用类目下（卡券 32、粮油调味 12、常温水果 12、休闲零食 10、茶叶 3），
-- 这一轮同样没碰：**它们一件都不在售**，而那些类目一个维度都没配。
-- 该迁走还是该下架是运营的判断；而 V228 之后「没配规格的二级类目不许启用」这道闸门
-- 已经挡住了「悄悄恢复一个空类目、货又流进来」这条路。

-- ── 1. 商品：补 templateNo / optionCodes，并改正组名 ──
UPDATE prd_goods SET spec_groups = '[{"name": "口径", "options": ["32cm"], "optionCodes": ["DM32"], "templateNo": "SD_DIAMETER"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712520091725' AND spec_groups = '[{"name":"规格","options":["32cm"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["4碗"], "optionCodes": ["C4"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713020197988' AND spec_groups = '[{"name":"规格","options":["4碗"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["2碗"], "optionCodes": ["C2"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713020199300' AND spec_groups = '[{"name":"规格","options":["2碗"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "容量", "options": ["1L"], "optionCodes": ["V1L"], "templateNo": "SD_VOLUME"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713020201366' AND spec_groups = '[{"name":"规格","options":["1L"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["1盅"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713030213165' AND spec_groups = '[{"name":"规格","options":["1盅"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "容量", "options": ["30ml"], "optionCodes": ["V30ML"], "templateNo": "SD_VOLUME"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713090271483' AND spec_groups = '[{"name":"规格","options":["30ml"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "容量", "options": ["60ml"], "optionCodes": ["V60ML"], "templateNo": "SD_VOLUME"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713100273988' AND spec_groups = '[{"name":"规格","options":["60ml"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "面积", "options": ["100㎡内"], "optionCodes": ["A100"], "templateNo": "SD_AREA"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713100279753' AND spec_groups = '[{"name":"规格","options":["100㎡内"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "面积", "options": ["10㎡"], "optionCodes": ["A10"], "templateNo": "SD_AREA"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713100281445' AND spec_groups = '[{"name":"规格","options":["10㎡"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["三人位"], "optionCodes": ["C3"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713110293804' AND spec_groups = '[{"name":"规格","options":["三人位"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "面积", "options": ["每㎡"], "optionCodes": ["A1"], "templateNo": "SD_AREA"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713120295783' AND spec_groups = '[{"name":"规格","options":["每㎡"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "长度", "options": ["1.8m"], "optionCodes": ["L18M"], "templateNo": "SD_LENGTH"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713120297614' AND spec_groups = '[{"name":"规格","options":["1.8m"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "面积", "options": ["100㎡"], "optionCodes": ["A100"], "templateNo": "SD_AREA"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713120299081' AND spec_groups = '[{"name":"规格","options":["100㎡"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "面积", "options": ["80㎡内"], "optionCodes": ["A80"], "templateNo": "SD_AREA"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713120301756' AND spec_groups = '[{"name":"规格","options":["80㎡内"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "次数", "options": ["单次"], "optionCodes": ["T1"], "templateNo": "SD_TIMES"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713130305610' AND spec_groups = '[{"name":"规格","options":["单次"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "次数", "options": ["单次"], "optionCodes": ["T1"], "templateNo": "SD_TIMES"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713130307041' AND spec_groups = '[{"name":"规格","options":["单次"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["单把"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713140321597' AND spec_groups = '[{"name":"规格","options":["单把"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "次数", "options": ["单次"], "optionCodes": ["T1"], "templateNo": "SD_TIMES"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713140323884' AND spec_groups = '[{"name":"规格","options":["单次"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "面积", "options": ["5㎡"], "optionCodes": ["A5"], "templateNo": "SD_AREA"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713140325596' AND spec_groups = '[{"name":"规格","options":["5㎡"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["每窗"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713140327690' AND spec_groups = '[{"name":"规格","options":["每窗"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "次数", "options": ["单次"], "optionCodes": ["T1"], "templateNo": "SD_TIMES"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713150331250' AND spec_groups = '[{"name":"规格","options":["单次"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "长度", "options": ["每米"], "optionCodes": ["L1M"], "templateNo": "SD_LENGTH"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713150333788' AND spec_groups = '[{"name":"规格","options":["每米"]}]';

-- ── 2. SKU：盖上值编号 ──
UPDATE prd_sku SET option_value_nos = '["SV_DIAMETER_DM32"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712520092747' AND market = 'CN' AND option_values = '["32cm"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C4"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713020198891' AND market = 'CN' AND option_values = '["4碗"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C2"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713020200365' AND market = 'CN' AND option_values = '["2碗"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_VOLUME_V1L"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713020202288' AND market = 'CN' AND option_values = '["1L"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713030214652' AND market = 'CN' AND option_values = '["1盅"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_VOLUME_V30ML"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713090272056' AND market = 'CN' AND option_values = '["30ml"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_VOLUME_V60ML"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713100274080' AND market = 'CN' AND option_values = '["60ml"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_AREA_A100"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713100280900' AND market = 'CN' AND option_values = '["100㎡内"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_AREA_A10"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713100282196' AND market = 'CN' AND option_values = '["10㎡"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C3"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713110294601' AND market = 'CN' AND option_values = '["三人位"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_AREA_A1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713120296336' AND market = 'CN' AND option_values = '["每㎡"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_LENGTH_L18M"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713120298806' AND market = 'CN' AND option_values = '["1.8m"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_AREA_A100"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713120300405' AND market = 'CN' AND option_values = '["100㎡"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_AREA_A80"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713120302730' AND market = 'CN' AND option_values = '["80㎡内"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_TIMES_T1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713130306319' AND market = 'CN' AND option_values = '["单次"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_TIMES_T1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713130308772' AND market = 'CN' AND option_values = '["单次"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713140322871' AND market = 'CN' AND option_values = '["单把"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_TIMES_T1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713140324772' AND market = 'CN' AND option_values = '["单次"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_AREA_A5"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713140326864' AND market = 'CN' AND option_values = '["5㎡"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713140328623' AND market = 'CN' AND option_values = '["每窗"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_TIMES_T1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713150332314' AND market = 'CN' AND option_values = '["单次"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_LENGTH_L1M"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713150334016' AND market = 'CN' AND option_values = '["每米"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
