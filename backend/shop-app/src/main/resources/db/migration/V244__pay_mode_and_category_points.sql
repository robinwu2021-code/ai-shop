-- 线下支付与类目积分的地基：两张新表 + 五处加列。
--
-- 本迁移**不改变任何行为** —— 所有新列都有默认值且暂时无人读写，
-- 新表暂时无人查。行为在后面几步接上（见 docs/technical/工单-线下支付与积分.md）。
-- 先落地基是刻意的：它是这条链上唯一一步写错也影响不到线上的。
--
-- 设计与理由都在 docs/technical/TDD-支付与积分总体方案.md，这里只记「为什么是这个形状」。

-- ── 1. 类目 × 支付方式：四层判定的第 ① 层 ──
--
-- **没有行 = 放行**，不是「没有行 = 禁止」。理由：一期只想用「主体资质」这一层做主力，
-- 其余三层默认放行；若反过来设计成白名单，上线当天就得先把 57 个类目全配一遍才有人能下单。
-- 需要禁某个类目时才插一行 allowed=0。
CREATE TABLE IF NOT EXISTS prd_category_pay_mode
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    category_no VARCHAR(64) NOT NULL,
    pay_mode    VARCHAR(16) NOT NULL COMMENT 'PayModes 取值域：ONLINE / OFFLINE',
    allowed     TINYINT     NOT NULL DEFAULT 1,
    tenant_no   VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at  DATETIME    NOT NULL,
    created_by  VARCHAR(64)          DEFAULT NULL,
    updated_at  DATETIME    NOT NULL,
    updated_by  VARCHAR(64)          DEFAULT NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cat_pay_mode (tenant_no, category_no, pay_mode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='类目 × 支付方式：没有行即放行，插 allowed=0 才是禁止';

-- ── 2. 类目积分规则 ──
--
-- 与 prd_category_spec 同构：**平台按类目统一管理**，商家不参与配置。
-- 依据是实测 —— 线上 199 件商品里，用商品级 points_config 配了积分的是 0 件。
-- 而运营配 30 个类目是做得到的事（规格那套现在 30/30 全配齐）。
--
-- earn_value 用**整数**：FIXED 存分，RATIO 存万分比（千分之一 = 10）。
-- 不用浮点 —— 金额与比例一旦用 double，对账时的分位差没人说得清，
-- 与 stl_bill.commission_rate 同一条规矩。
CREATE TABLE IF NOT EXISTS prd_category_points
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    category_no VARCHAR(64) NOT NULL,
    earn_mode   VARCHAR(16) NOT NULL COMMENT 'FIXED 定额（分） / RATIO 按成交额比例（万分比）',
    earn_value  BIGINT      NOT NULL,
    tenant_no   VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at  DATETIME    NOT NULL,
    created_by  VARCHAR(64)          DEFAULT NULL,
    updated_at  DATETIME    NOT NULL,
    updated_by  VARCHAR(64)          DEFAULT NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cat_points (tenant_no, category_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='类目积分规则：平台统一按类目管理，商家不配';

-- ── 3. 商品：支持哪些支付方式（第 ④ 层）──
--
-- 默认 ["ONLINE"]，与现状一致：存量商品一件都不会因为这次迁移变成支持线下付。
-- ⚠️ 取值域由 PayModes 常量类约束。**这一列不能重蹈 fulfillments 的覆辙** ——
-- 那一列曾经是「无取值域的自由 JSON、建品时被写死、商家改不了」，
-- 于是「这件商品支持怎么送」在商品侧从没真正表达过。所以取值域常量、建品可选、
-- 下单校验三件事要在同一批里做完。
ALTER TABLE prd_goods ADD COLUMN pay_modes VARCHAR(128) NOT NULL DEFAULT '["ONLINE"]';

-- ── 4. 门店：线下收款与货到付款两个开关（第 ③ 层）──
--
-- **都默认关**，与 pay_modes 的默认放行不同 —— 这两个是「这家店愿不愿意/敢不敢」，
-- 属于商家的经营决定，不该由平台替他打开。
--
-- 货到付款单独一个开关而不是跟着线下支付一起开：它是整张组合表里风险最高的一格
-- （拒收、跑单，损失全在商家），要商家在承担得起的时候自己打开。
ALTER TABLE mch_store ADD COLUMN offline_pay_enabled TINYINT NOT NULL DEFAULT 0
    COMMENT '这家店是否接受线下（当面）收款';
ALTER TABLE mch_store ADD COLUMN cod_enabled TINYINT NOT NULL DEFAULT 0
    COMMENT '货到付款（商家自送 + 线下付）。单独开关：整张组合表里风险最高的一格';

-- ── 5. 订单：线下收款留痕 ──
--
-- 出纠纷时要说得清「是谁在什么时候点的确认收款」。平台不碰这笔钱，
-- 所以平台能提供的只有这条留痕 —— 缺了它，争议就变成两边各执一词。
ALTER TABLE ord_order ADD COLUMN offline_confirmed_by VARCHAR(64) DEFAULT NULL
    COMMENT '线下收款的确认人（B 端操作员）';
ALTER TABLE ord_order ADD COLUMN offline_confirmed_at BIGINT DEFAULT NULL
    COMMENT '线下收款的确认时间';

-- ── 6. 结算：让掉的佣金，只记不扣 ──
--
-- 线下支付已拍板**不抽佣**。所以这一列不是「为了将来去收」，是**为了知道让了多少** ——
-- 缺了它，连「线下这部分生意值多少钱」都算不出来，将来无论继续免还是重新定价都没有依据。
-- 与 commission_minor 并列才一眼看得出「本该收多少、实际收了 0」。
ALTER TABLE stl_bill ADD COLUMN waived_commission_minor BIGINT NOT NULL DEFAULT 0
    COMMENT '线下单让掉的佣金：只记不扣';

-- ── 没有加的列，记一笔 ──
--
-- ord_order.pay_scene（下单端）**V1 baseline 就有**，注释写着
-- 'MP_WECHAT/MP_ALIPAY/IOS/ANDROID/H5'，缺的只是写入（全代码库搜不到一次 setPayScene）。
-- 所以第 1 步只补 Java 实体字段，不加列。
