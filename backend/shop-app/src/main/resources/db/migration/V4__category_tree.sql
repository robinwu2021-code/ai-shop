-- 类目树补齐（方案见 docs/technical/类目树补齐方案.md）
--
-- 背景：prd_category 建表就在，tree() 也实现了，但**一直是空表** ——
-- 于是 GET /mp/category/tree 返回 []，商家上架时没有类目可选，
-- 类目资质准入也就无从谈起。这一版补上字段、数据与商家侧的授权字段。
--
-- ⚠️ 类目树与 prd_goods.type（五品类枚举）是**两个正交维度**，不是重复：
--    type     = 履约与合规（冷链 / 不发货 / iOS 可售规则），平台硬编码
--    category = 导购与准入（归到哪、要什么资质），运营可维护

-- ── 1. 类目：补三个字段 ──────────────────────────────────────────────

-- 五品类录入模板：决定商家录入这个类目的商品时看到哪些字段。
-- 与已有的 attr_template（JSON 属性模板）不是一回事：那个是字段清单，这个是模板**类型**。
ALTER TABLE prd_category
    ADD COLUMN template VARCHAR(16) NOT NULL DEFAULT 'STANDARD'
        COMMENT 'STANDARD/FRESH/SERVICE/VIRTUAL/VOUCHER：决定商家录入时看到哪些字段' AFTER sort;

-- 资质校验的**判据**。与 qualification_required 分开是有意的：
-- 后者是给人看的文案（「食品经营许可证」），拿文案做判据会退化成
-- 「类目号以 CAT1 开头就算需要生鲜资质」这类前缀魔法 —— 看着在校验，实际几乎总是通过。
ALTER TABLE prd_category
    ADD COLUMN required_code VARCHAR(32) DEFAULT NULL
        COMMENT '经营该类目所需的经营类目编码，对应 mch_entity.category_codes。空=无门槛' AFTER qualification_required;

-- 三语里先落 en。ar 二期再加 —— 不预留一个长期为空的列，
-- 空列会让人以为功能已经有了，只是没人填。
ALTER TABLE prd_category
    ADD COLUMN name_en VARCHAR(64) DEFAULT NULL COMMENT '英文名。缺失时按 R9 回落规则展示中文名' AFTER name;

-- ── 2. 商家：已获授权的经营类目 ──────────────────────────────────────

-- 入驻时申请、平台审核时授权。空/NULL = 没有任何特许类目，
-- 只能上架 required_code 为空的类目。
ALTER TABLE mch_entity
    ADD COLUMN category_codes VARCHAR(512) DEFAULT NULL
        COMMENT 'JSON 数组：已获授权的经营类目编码，如 ["FRESH_VEG","FOOD"]。与 prd_category.required_code 比对' AFTER industry;
-- ⚠️ 用 VARCHAR 存 JSON 而不是 JSON 列，与本库 images/risk_flags 等同一惯例：
-- 集成测试跑在 H2 上，而 H2 的 JSON 类型会把绑定进去的字符串**再包一层引号**，
-- 读回来是 "[\"FRESH_VEG\"]" 而不是数组，反序列化直接失败。
-- 失败被兜底成「没有任何授权」，于是授权写成功了、接口返回 0、审计也记了，
-- 商家却依然上不了架 —— 一个全链路都说成功的故障。

-- ── 3. 类目树种子 ───────────────────────────────────────────────────
--
-- 编号与 ops-web 的 mock（lib/mock/db/product.ts）**保持一致**：
-- 前端 mock 与真库对得上，联调时不用在两套编号之间换算，
-- 也不会出现「mock 上跑得通、连真库就找不到类目」这种最难查的一类错配。
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
-- 一级
('CAT100', NULL,     1, '食品生鲜', 'Fresh Food', NULL, 10, 'FRESH',    NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT200', NULL,     1, '日用百货', 'Household',  NULL, 20, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT300', NULL,     1, '生活服务', 'Services',   NULL, 30, 'SERVICE',  NULL, '["家电维修资质"]', 'SERVICE_REPAIR', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT400', NULL,     1, '卡券',     'Vouchers',   NULL, 40, 'VOUCHER',  NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
-- 二级
('CAT110', 'CAT100', 2, '蔬菜', 'Vegetables',     NULL, 10, 'FRESH',    NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT120', 'CAT100', 2, '水果', 'Fruits',         NULL, 20, 'FRESH',    NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT210', 'CAT200', 2, '纸品清洁', 'Cleaning',   NULL, 10, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
-- 三级（挂资质的都在这一层：资质是按最细的经营范围批的）
('CAT111', 'CAT110', 3, '叶菜',   'Leafy Greens', NULL, 10, 'FRESH', NULL, '["食品经营许可证"]', 'FRESH_VEG',   'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT112', 'CAT110', 3, '根茎菜', 'Root Veg',     NULL, 20, 'FRESH', NULL, '["食品经营许可证"]', 'FRESH_VEG',   'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT121', 'CAT120', 3, '浆果',   'Berries',      NULL, 10, 'FRESH', NULL, '["食品经营许可证"]', 'FRESH_FRUIT', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
