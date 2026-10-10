-- 规格模板下沉到**类目**粒度。
--
-- 为什么必须下沉：品类只有 3 个，而二级类目有 32 个，且分布极不均 ——
-- STANDARD 一个品类就盖住了 18 个（日用百货 8 + 电子产品 3 + 食品饮料 5 + 鲜花绿植 2）。
-- 于是手机数码、鲜花、婴幼儿食品、宠物用品共用同一套模板，
-- 而那套模板是「包装：袋装 / 瓶装 / 盒装 / 罐装」——
-- 给一副蓝牙耳机推荐「瓶装」，商家看一眼就会认定这功能没做完。
--
-- 做成**两层**而不是把品类那层删掉：
--   · category_no IS NULL → 按 category_type 匹配，是兜底，覆盖全部 32 个类目
--   · category_no = 具体类目 → 类目级，**优先于兜底**（同名规格组把兜底顶掉）
-- 只给「兜底明显不适用」的类目配第二层，不是每个类目都配 ——
-- 全配一遍的维护量会让运营放弃更新，最后退化成一堆没人改的过期数据。

ALTER TABLE prd_spec_template
    ADD COLUMN category_no VARCHAR(64) DEFAULT NULL COMMENT '类目级模板归属；NULL = 按 category_type 兜底' AFTER category_type;

-- 索引跟着查询走：端上一次查询要同时取「这个类目的」与「这个品类兜底的」两批
CREATE INDEX idx_spec_tpl_category_no ON prd_spec_template (category_no);

-- 类目级模板。category_type 仍然填上：兜底查询按品类过滤时，
-- 这些行也该跟着自己的品类走，而不是变成一批谁都查不到的孤儿。
INSERT INTO prd_spec_template
(template_no, scope, category_type, category_no, name, options, entity_no, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
-- ── 食品生鲜下：兜底是「重量 / 包装」，但液体按容量卖、茶叶按小克重卖
('SPT_CAT150_VOL', 'PLATFORM', 'FRESH', 'CAT150', '容量',
 '[{"code":"V250ML","label":"250ml"},{"code":"V500ML","label":"500ml"},{"code":"V750ML","label":"750ml"},{"code":"V1L","label":"1L"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT160_WT', 'PLATFORM', 'FRESH', 'CAT160', '重量',
 '[{"code":"W50G","label":"50g"},{"code":"W100G","label":"100g"},{"code":"W250G","label":"250g"},{"code":"W500G","label":"500g"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT180_VOL', 'PLATFORM', 'FRESH', 'CAT180', '容量',
 '[{"code":"V250ML","label":"250ml"},{"code":"V1L","label":"1L"},{"code":"V1500ML","label":"1.5L"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
-- ── 日用百货下
('SPT_CAT230_VOL', 'PLATFORM', 'NORMAL', 'CAT230', '容量',
 '[{"code":"V50ML","label":"50ml"},{"code":"V100ML","label":"100ml"},{"code":"V200ML","label":"200ml"},{"code":"V500ML","label":"500ml"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT250_SIZE', 'PLATFORM', 'NORMAL', 'CAT250', '尺码',
 '[{"code":"SZS","label":"S"},{"code":"SZM","label":"M"},{"code":"SZL","label":"L"},{"code":"SZXL","label":"XL"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT270_WT', 'PLATFORM', 'NORMAL', 'CAT270', '重量',
 '[{"code":"W500G","label":"500g"},{"code":"W1500G","label":"1.5kg"},{"code":"W5KG","label":"5kg"},{"code":"W10KG","label":"10kg"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
-- ── 电子产品下：颜色与存储是这一类真正会分 SKU 的两个维度
('SPT_CAT610_COLOR', 'PLATFORM', 'NORMAL', 'CAT610', '颜色',
 '[{"code":"CLRBLACK","label":"黑色"},{"code":"CLRWHITE","label":"白色"},{"code":"CLRBLUE","label":"蓝色"},{"code":"CLRPINK","label":"粉色"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT610_STOR', 'PLATFORM', 'NORMAL', 'CAT610', '存储',
 '[{"code":"S64G","label":"64G"},{"code":"S128G","label":"128G"},{"code":"S256G","label":"256G"},{"code":"S512G","label":"512G"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT630_LEN', 'PLATFORM', 'NORMAL', 'CAT630', '长度',
 '[{"code":"L05M","label":"0.5m"},{"code":"L1M","label":"1m"},{"code":"L2M","label":"2m"},{"code":"L3M","label":"3m"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
-- ── 食品饮料下
('SPT_CAT720_WT', 'PLATFORM', 'NORMAL', 'CAT720', '重量',
 '[{"code":"W100G","label":"100g"},{"code":"W250G","label":"250g"},{"code":"W500G","label":"500g"},{"code":"W1KG","label":"1kg"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT730_VOL', 'PLATFORM', 'NORMAL', 'CAT730', '容量',
 '[{"code":"V330ML","label":"330ml"},{"code":"V500ML","label":"500ml"},{"code":"V1L","label":"1L"},{"code":"VCASE","label":"整箱"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT750_STAGE', 'PLATFORM', 'NORMAL', 'CAT750', '段位',
 '[{"code":"ST1","label":"1段"},{"code":"ST2","label":"2段"},{"code":"ST3","label":"3段"},{"code":"ST4","label":"4段"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
-- ── 鲜花绿植下：鲜花按支数、绿植按盆型，兜底那套「件数 / 包装」完全用不上
('SPT_CAT810_STEM', 'PLATFORM', 'NORMAL', 'CAT810', '支数',
 '[{"code":"N9","label":"9支"},{"code":"N11","label":"11支"},{"code":"N19","label":"19支"},{"code":"N33","label":"33支"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT820_SIZE', 'PLATFORM', 'NORMAL', 'CAT820', '尺寸',
 '[{"code":"PSMALL","label":"小盆"},{"code":"PMID","label":"中盆"},{"code":"PLARGE","label":"大盆"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
-- ── 生活服务下：兜底是「时长 / 人数」，但保洁按房型计价、宠物洗护按体型计价
('SPT_CAT310_ROOM', 'PLATFORM', 'SERVICE', 'CAT310', '房型',
 '[{"code":"R1","label":"一居"},{"code":"R2","label":"两居"},{"code":"R3","label":"三居"},{"code":"R4","label":"四居及以上"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT350_PET', 'PLATFORM', 'SERVICE', 'CAT350', '体型',
 '[{"code":"PETS","label":"小型犬"},{"code":"PETM","label":"中型犬"},{"code":"PETL","label":"大型犬"},{"code":"PETCAT","label":"猫"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
