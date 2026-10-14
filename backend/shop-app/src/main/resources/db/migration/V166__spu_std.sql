-- 标准品库（TDD-标准品库）：平台维护的标准商品，商家引用建品。
--
-- 在此之前系统里**只有商家品**：三家店各自录「农夫山泉 550ml」，得到三个毫无关系的商品。
-- 代价一个显性一个隐性：
--   显性 —— 商家每建一件货都要从零填标题、图、规格，这是入驻之后最劝退的一步；
--   隐性 —— **跨店不可比**。optionCode（B-4.5）做了「一期只写入不消费」，
--           而它要消费的前提是「同一件货在不同店里指向同一个东西」，
--           没有标准品，那个前提永远不成立。
--
-- 第二条才是做它的真正理由。
--
-- 一期是**复制 + 溯源**，不是完整的引用式：取用时把字段填进商家品，
-- 标准品之后改了不回流（回流要配审计与回滚，先看商家用不用）。
-- 与「直接复制」的差别只剩 std_no 这一条线 —— 而那条线正是聚合与统计要用的。

CREATE TABLE IF NOT EXISTS prd_spu_std
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    std_no        VARCHAR(64)  NOT NULL COMMENT '标准品编号',

    -- 必填。形态（NORMAL/FRESH/...）由类目派生，与商家品同一条规则；
    -- 商家取用时**类目不可改** —— 改了形态就变了，那就不是这个标准品了
    category_no   VARCHAR(64)  NOT NULL COMMENT '所属类目，形态由它派生',

    title         VARCHAR(255) NOT NULL COMMENT '标准标题，商家可改',
    title_i18n    TEXT                  DEFAULT NULL COMMENT 'JSON {"en":…,"ar":…}',
    subtitle      VARCHAR(255)          DEFAULT NULL,
    cover         VARCHAR(512)          DEFAULT NULL,
    images        TEXT                  DEFAULT NULL COMMENT 'JSON 数组',

    -- 与 prd_goods.spec_groups 同构，**每个选项必须带 optionCode** ——
    -- code 是这张表存在的唯一理由：没有它，标准品只是个填表助手
    spec_groups   TEXT                  DEFAULT NULL COMMENT 'JSON，必须带 optionCode',

    -- 别名/品牌/俗称，空格分隔。一期按名称搜索（不做条码：生鲜、散装、
    -- 手工品本来就没有条码，而那是这个平台的主力）
    keywords      VARCHAR(512)          DEFAULT NULL COMMENT '搜索用别名，空格分隔',

    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED',
    -- 被引用次数。只服务运营侧排序与去重判断，**不参与任何校验** ——
    -- 所以由定时统计刷新即可，不挂在建品的写路径上
    ref_count     INT          NOT NULL DEFAULT 0 COMMENT '被引用次数，仅供运营排序',

    tenant_no     VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME     NOT NULL,
    created_by    VARCHAR(64)           DEFAULT NULL,
    updated_at    DATETIME     NOT NULL,
    updated_by    VARCHAR(64)           DEFAULT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_spu_std_no UNIQUE (std_no),
    KEY idx_spu_std_category (category_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='平台标准品：商家引用建品的模子，无价无库存';

-- 商家品上的溯源引用。**可空** —— 自建品照旧，标准库搜不到的东西不能变成建不了。
ALTER TABLE prd_goods
    ADD COLUMN std_no VARCHAR(64) DEFAULT NULL COMMENT '引用的标准品；NULL = 自建品';

-- ── 种子 ──────────────────────────────────────────────────────────
--
-- 只给一小批，覆盖已有类目里最常见的品，**够验证链路而已**。
-- ⚠️ 真正决定这个功能成不成的是覆盖率，而覆盖率全靠运营手录 ——
-- 「谁来录、录多少才够」是产品问题，不是这条迁移能解决的（见 TDD §四 风险 1）。
--
-- 规格里的 code 沿用平台规格模板那套口径（W1JIN / W2JIN / …）：
-- 跨店可比靠的就是它们在不同商家的商品上是同一个字符串。
INSERT INTO prd_spu_std
(std_no, category_no, title, title_i18n, subtitle, cover, images, spec_groups, keywords,
 status, ref_count, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('STD1001', 'CAT111', '本地菠菜', '{"en":"Local Spinach"}', '当季叶菜', NULL, NULL, '[{"name":"重量","options":["500g","1斤","2斤"],"optionCodes":["W500G","W1JIN","W2JIN"]}]', '菠菜 波斯菜 叶菜', 'ACTIVE', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('STD1002', 'CAT111', '小白菜', '{"en":"Baby Bok Choy"}', '当季叶菜', NULL, NULL, '[{"name":"重量","options":["500g","1斤","2斤"],"optionCodes":["W500G","W1JIN","W2JIN"]}]', '小白菜 青菜 叶菜', 'ACTIVE', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('STD1003', 'CAT111', '生菜', '{"en":"Lettuce"}', '当季叶菜', NULL, NULL, '[{"name":"重量","options":["500g","1斤"],"optionCodes":["W500G","W1JIN"]}]', '生菜 莴苣 叶菜', 'ACTIVE', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('STD1011', 'CAT112', '土豆', '{"en":"Potato"}', '根茎菜', NULL, NULL, '[{"name":"重量","options":["1斤","2斤","5斤"],"optionCodes":["W1JIN","W2JIN","W5JIN"]}]', '土豆 马铃薯 洋芋', 'ACTIVE', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('STD1012', 'CAT112', '胡萝卜', '{"en":"Carrot"}', '根茎菜', NULL, NULL, '[{"name":"重量","options":["1斤","2斤"],"optionCodes":["W1JIN","W2JIN"]}]', '胡萝卜 红萝卜', 'ACTIVE', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('STD1013', 'CAT112', '白萝卜', '{"en":"Daikon"}', '根茎菜', NULL, NULL, '[{"name":"重量","options":["1斤","2斤"],"optionCodes":["W1JIN","W2JIN"]}]', '白萝卜 萝卜', 'ACTIVE', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('STD1021', 'CAT121', '蓝莓', '{"en":"Blueberry"}', '当季浆果', NULL, NULL, '[{"name":"规格","options":["125g/盒","250g/盒"],"optionCodes":["P125G","P250G"]}]', '蓝莓 浆果', 'ACTIVE', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('STD1022', 'CAT121', '草莓', '{"en":"Strawberry"}', '当季浆果', NULL, NULL, '[{"name":"规格","options":["250g/盒","500g/盒"],"optionCodes":["P250G","P500G"]}]', '草莓 浆果', 'ACTIVE', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('STD1031', 'CAT120', '苹果', '{"en":"Apple"}', '常温水果', NULL, NULL, '[{"name":"重量","options":["2斤","5斤"],"optionCodes":["W2JIN","W5JIN"]}]', '苹果 富士', 'ACTIVE', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('STD1032', 'CAT120', '香蕉', '{"en":"Banana"}', '常温水果', NULL, NULL, '[{"name":"重量","options":["2斤","5斤"],"optionCodes":["W2JIN","W5JIN"]}]', '香蕉', 'ACTIVE', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('STD2001', 'CAT210', '抽纸', '{"en":"Facial Tissue"}', '家用抽取式面巾纸', NULL, NULL, '[{"name":"规格","options":["3包","6包","12包"],"optionCodes":["B3","B6","B12"]}]', '抽纸 面巾纸 纸巾', 'ACTIVE', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('STD2002', 'CAT210', '卷纸', '{"en":"Toilet Roll"}', '家用卫生卷纸', NULL, NULL, '[{"name":"规格","options":["6卷","12卷"],"optionCodes":["B6","B12"]}]', '卷纸 卫生纸 手纸', 'ACTIVE', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('STD2003', 'CAT210', '洗洁精', '{"en":"Dish Soap"}', '厨房清洁', NULL, NULL, '[{"name":"规格","options":["500ml","1L"],"optionCodes":["V500ML","V1L"]}]', '洗洁精 洗涤灵', 'ACTIVE', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('STD2004', 'CAT210', '洗衣液', '{"en":"Laundry Detergent"}', '衣物清洁', NULL, NULL, '[{"name":"规格","options":["1L","2L","3L"],"optionCodes":["V1L","V2L","V3L"]}]', '洗衣液 洗涤剂', 'ACTIVE', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
