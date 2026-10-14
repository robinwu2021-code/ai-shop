-- 类目降二级 + 门店经营类目（TDD-分类模型重构 §三 · TDD-品类约束全链路 §三）。
--
-- 为什么降到两级：
--   · **商家「选自己卖哪几类」这一步才可能存在** —— 三级树让它变成一个要展开两层的操作，
--     而商家心里的答案就是「蔬菜、水果」这一层；
--   · 叶菜 / 根茎菜的粒度服务的是搜索导购与比价，而一个社区就几十家店，
--     那个粒度带来的只有录入负担。
--
-- 代价是**资质粒度变粗**：required_code 从三级（叶菜→FRESH_VEG）上移到二级（蔬菜→FRESH_VEG）。
-- 判据没变，只是范围从「叶菜」扩到「蔬菜」—— 而这两者要的本来就是同一张食品经营许可证。

-- ── 1. 资质码上移到二级 ───────────────────────────────────────────
UPDATE prd_category SET required_code = 'FRESH_VEG',
       qualification_required = '["食品经营许可证"]' WHERE category_no = 'CAT110';
UPDATE prd_category SET required_code = 'FRESH_FRUIT',
       qualification_required = '["食品经营许可证"]' WHERE category_no = 'CAT120';

-- ── 2. 引用三级的商品与标准品上移到父级 ───────────────────────────
--
-- ⚠️ prd_spu_std 一定要一起刷：V166 的标准品种子引用了 CAT111/112/121，
-- 不刷的话它们指向已归档的类目，而商家搜到那条标准品就**建不出品**
-- （建品时类目查无此项 → CATEGORY_NOT_FOUND），错却在运营录的那一行上。
UPDATE prd_goods   SET category_no = 'CAT110' WHERE category_no IN ('CAT111', 'CAT112');
UPDATE prd_goods   SET category_no = 'CAT120' WHERE category_no = 'CAT121';
UPDATE prd_spu_std SET category_no = 'CAT110' WHERE category_no IN ('CAT111', 'CAT112');
UPDATE prd_spu_std SET category_no = 'CAT120' WHERE category_no = 'CAT121';

-- ── 3. 三级节点归档而不是删除 ─────────────────────────────────────
--
-- prd_goods 上没有历史类目的留痕，删掉之后「这件商品当初归在哪」就永远查不回来了。
-- 归档只是让它不再出现在选择器与树里。
UPDATE prd_category SET status = 'ARCHIVED' WHERE level = 3;

-- ── 4. 门店经营类目 ───────────────────────────────────────────────
--
-- **挂门店不挂主体**：被资质约束的是门店，货架当然也在门店上。
-- 而商品仍然挂主体（ADR-011 不动）—— 这两件事不矛盾，因为约束落在
-- 「上架到门店」那一步（prd_store_goods 那一层），不在建品那一步。
-- 一句话：建品看主体，上架看门店。
--
-- 与 prd_store_goods / prd_store_stock 的语义**不同**：那两张是「覆盖」
-- （有行才按店算），这张是「声明」（这家店打算卖哪几类）。
-- 写成覆盖语义的话，一个类目都没配的新店什么都上不了架 —— 而那正是新店的初始状态。
CREATE TABLE IF NOT EXISTS mch_store_category
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    store_no     VARCHAR(64) NOT NULL COMMENT '门店',
    entity_no    VARCHAR(64) NOT NULL COMMENT '主体，数据域锚点',
    category_no  VARCHAR(64) NOT NULL COMMENT '引用平台类目',
    -- 自定义显示名只是**皮**：底下的 category_no 不变，商家把「蔬菜」叫成「今日现摘」，
    -- 跨店聚合照常成立。自由命名的分组做不到这一点
    display_name VARCHAR(64)          DEFAULT NULL COMMENT '空 = 用平台类目名',
    sort         INT         NOT NULL DEFAULT 0 COMMENT '店铺页里的顺序，商家拖动改的就是它',
    enabled      TINYINT     NOT NULL DEFAULT 1,
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    created_by   VARCHAR(64)          DEFAULT NULL,
    updated_at   DATETIME    NOT NULL,
    updated_by   VARCHAR(64)          DEFAULT NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_store_category UNIQUE (store_no, category_no),
    KEY idx_store_category_entity (entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='门店经营类目：这家店打算卖哪几类';
