-- 一期主数据收敛（方案见 docs/technical/TDD-一期主数据收敛.md）
--
-- 背景：一期按**自营模式**上线小程序（ADR-012 B 方案）—— 平台是销售者，商家是供应商。
-- 因此商家能选的行业、能覆盖的范围、能上架的类目，**全部不能超出平台自己营业执照的经营范围**：
-- 超出一件，违法的是平台，不是那家菜摊。
--
-- ⚠️ 本迁移**一律用停用（enabled=0 / status=INACTIVE），不删行**。
--    拿到 EDI 切平台模式时，运营在后台逐条打开即可 —— 不需要再来一次迁移。
--    删掉的话，存量商家的 industry 会指向不存在的行，而切回去要重建全部数据。
--
-- 顺序不能改（V6 的教训）：**先补节点、再改指、最后停用**。
-- 反了会留下 category_no 指向已停用节点的商品 —— 既不为空、又不属于任何可用类目，
-- 类目筛选与准入校验会一起漏掉它们。

-- ── 1. 新增授权码 ───────────────────────────────────────────────────
--
-- 授权码按**能力**而不是类目节点发（V5 的设计）：类目树会重构，
-- 而「能不能卖预包装食品」这件事不会。

INSERT INTO sys_auth_code
(code, name, required_qualification, sort, enabled, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('PACKAGED_FOOD', '预包装食品', '仅销售预包装食品备案', 25, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
-- 家政无前置许可：执照的「家政服务」一项就够，不要求证件。
-- 留空而不是随便填一个证名 —— 填了运营就会去要一张不存在的证
('HOUSEKEEPING',  '家政服务', NULL,                     65, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);

-- ── 2. 新增类目节点 ─────────────────────────────────────────────────
--
-- 每个叶子后面都能在营业执照里指到一句对应表述，注释里标出来 ——
-- 半年后有人想加「猪肉」时，这份对照是他判断能不能加的唯一依据。

INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
-- 水果补一个常温档：浆果之外的苹果、橙子占了果摊的大头，只有「浆果」等于没有水果
('CAT122', 'CAT120', 3, '常温水果', 'Fruits (Ambient)', NULL, 20, 'FRESH', NULL,
 '["营业执照（食用农产品）"]', 'FRESH_FRUIT', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),

-- 预包装食品 ← 执照「预包装食品(不含复热预包装食品)批发兼零售」「茶叶的批发兼零售」
('CAT130', 'CAT100', 2, '预包装食品', 'Packaged Food', NULL, 30, 'STANDARD', NULL,
 NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT131', 'CAT130', 3, '粮油调味', 'Grain & Oil',    NULL, 10, 'STANDARD', NULL,
 '["仅销售预包装食品备案"]', 'PACKAGED_FOOD', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT132', 'CAT130', 3, '休闲零食', 'Snacks',         NULL, 20, 'STANDARD', NULL,
 '["仅销售预包装食品备案"]', 'PACKAGED_FOOD', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT133', 'CAT130', 3, '茶叶',     'Tea',            NULL, 30, 'STANDARD', NULL,
 '["仅销售预包装食品备案"]', 'PACKAGED_FOOD', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),

-- 日用百货 ← 执照「日用品、家具、纺织品、陶瓷制品」「化妆品及卫生用品」
('CAT220', 'CAT200', 2, '家居用品', 'Home',      NULL, 20, 'STANDARD', NULL,
 NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT230', 'CAT200', 2, '个护化妆', 'Personal Care', NULL, 30, 'STANDARD', NULL,
 NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),

-- 家政 ← 执照「家政服务」。
-- **二级带 required_code 是有意的**：服务类目只有两级，硬凑三级是为了对齐而对齐。
-- 约定已由「资质挂三级」改为「资质挂叶子节点」（TDD §0 定稿 2）
('CAT310', 'CAT300', 2, '家政保洁', 'Housekeeping', NULL, 10, 'SERVICE', NULL,
 NULL, 'HOUSEKEEPING', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);

-- ── 3. 改指：一级类目上不该挂商品 ───────────────────────────────────
--
-- 演示数据里有商品直接挂在 CAT300（一级）上。下一步要清掉 CAT300 的 required_code，
-- 而准入校验读的就是商品所在类目的那一列 —— 不改指的话这些商品会变成「无门槛」，
-- 一个卖家政服务的商品谁都能上架。

UPDATE prd_goods SET category_no = 'CAT310' WHERE category_no = 'CAT300';

-- ── 4. 修 D2：CAT300 的资质码挂错了层 ───────────────────────────────
--
-- V4 把 SERVICE_REPAIR（家电维修资质）挂在了**一级**「生活服务」上，
-- 而 V4 自己的注释写着「挂资质的都在三级」。后果：一期唯一要上的服务品类是家政，
-- 它会被一张**它不需要的**维修资质挡住 —— 而商家看到的只是「你还没有资质授权」。

UPDATE prd_category
SET required_code = NULL, qualification_required = NULL
WHERE category_no = 'CAT300';

-- ── 5. 停用超出执照范围的类目 ───────────────────────────────────────
--
-- 卡券：执照没有预付卡相关项，且储值卡触发《单用途商业预付卡管理办法》——
-- 自营模式下这笔负债直接记在平台账上，比平台模式重得多。

-- 用 ARCHIVED 而不是另造一个 INACTIVE：CategoryServiceImpl 认的就是这个值
-- （归档时间由它推算，`unarchive` 也只认它）。多一个没人写入的状态值，
-- 症状是运营端看到一条「既不在售、又没有归档时间」的类目，谁也说不清它算什么
UPDATE prd_category SET status = 'ARCHIVED' WHERE category_no = 'CAT400';

-- ── 6. 授权码：改文案 + 停用 ────────────────────────────────────────
--
-- 果蔬一期走的是**初级农产品**口径：执照「水果、蔬菜 批发与零售」就够，
-- 不需要食品经营许可证。留着原文案的直接后果是运营授权时去要一张不需要的证，
-- 而商家为了这张证卡在入驻上 —— 一个纯粹由文案造成的阻塞。

UPDATE sys_auth_code SET required_qualification = '营业执照（食用农产品）'
WHERE code IN ('FRESH_VEG', 'FRESH_FRUIT');

UPDATE prd_category SET qualification_required = '["营业执照（食用农产品）"]'
WHERE required_code IN ('FRESH_VEG', 'FRESH_FRUIT');

-- 停用：执照覆盖不到的三个能力。
--   FRESH_DAIRY 冷链乳品 —— 要食品经营许可证 + 经营场所现场核查，而平台没有仓
--   FOOD        熟食加工 —— 同上，且执照根本没有这一项
--   SERVICE_REPAIR 维修 —— 执照只有「家政服务」，没有维修
UPDATE sys_auth_code SET enabled = 0 WHERE code IN ('FRESH_DAIRY', 'FOOD', 'SERVICE_REPAIR');

-- ── 7. 行业：只留执照能卖的两个 ─────────────────────────────────────
--
-- remark 会显示在运营端的行业页上，所以写的是**停用理由**而不是「已停用」——
-- 三个月后要放开时，看这一行就知道当初卡在哪、现在够不够条件放开。

UPDATE sys_industry SET enabled = 0,
    remark = '一期停用：平台执照无餐饮服务与热食制售（自营模式下平台是销售者）。拿到相应许可后可放开'
WHERE industry = 'CATERING';

UPDATE sys_industry SET enabled = 0,
    remark = '一期停用：平台执照无相关经营项'
WHERE industry IN ('ENTERTAINMENT', 'TRANSPORT');

UPDATE sys_industry SET enabled = 0,
    remark = '一期停用：不上虚拟商品与卡券（iOS 小程序虚拟支付受限）'
WHERE industry = 'ONLINE';

-- 「其他」这一档在自营模式下必须停：它等于「平台不知道自己在卖什么」。
-- 防的是运营图省事一律选它，把上面所有准入判断整个绕过去
UPDATE sys_industry SET enabled = 0,
    remark = '一期停用：自营模式下「其他」等于平台不清楚自己在销售什么，无法对应执照经营范围'
WHERE industry = 'OTHER';

UPDATE sys_industry SET
    remark = '一期启用：执照含日用品、水果、蔬菜、预包装食品、茶叶 批发与零售'
WHERE industry = 'RETAIL';

-- 启用但**仅家政**：执照没有维修与洗衣。行业这一层拦不住细分品类，
-- 靠类目树卡（SERVICE_REPAIR 已停用），所以 remark 要把这个边界说出来
UPDATE sys_industry SET
    remark = '一期启用，但仅家政：执照含「家政服务」，无维修/洗衣。细分品类由类目树限制'
WHERE industry = 'LIFE_SERVICE';

-- ── 8. 经营范围启用白名单 ───────────────────────────────────────────
--
-- SERVICE_SCOPE 是 shared 里的枚举（COMMUNITY/CITY/PLATFORM），**值域不动** ——
-- 改枚举会动 glossary.test.ts 与三端契约，而我们要表达的不是「这个值不存在」，
-- 是「这个值这一期不开放」。两件事分开：
--   值域   → 代码的事实，写入口硬校验
--   白名单 → 运营的决定，存这里，可在后台改
-- 合成一个的话，运营在后台打开 PLATFORM 时会顺手获得「写入任意字符串」的能力。

INSERT INTO sys_setting
(setting_key, setting_value, remark, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('merchant.service-scope-enabled', '["COMMUNITY","CITY"]',
 '一期自营模式：PLATFORM 档没有商品形态支撑（无虚拟商品、无卡券、无平台自营快递品）。切平台模式后放开',
 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
