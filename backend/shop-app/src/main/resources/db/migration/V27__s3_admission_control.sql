-- S3 弱主体准入控制：保证金 / 限品类 / 限额（落地清单 F-6，方案 §7.7）
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么是这三样，为什么必须一起上
-- ─────────────────────────────────────────────────────────────────────────────
-- 平台**无仓、不碰货**，「自营」只是资质代持的外壳。所以准入矩阵里最弱的一档
-- （S3 = legal_form=MICRO：小微、免登记、无票）**没有「入平台仓让平台验货」这条出路**
-- ——那个仓根本不存在。平台在法律上是销售主体、承担全部产品责任，却没有任何
-- 货物控制手段。这个缺口填不上，只能用**准入**和**钱**去补。
--
-- 三样单独上都不成立：
--   · 只有保证金 → 成交额不封顶，敞口无上限，那笔钱形同虚设
--   · 只有限额   → 出事没钱赔
--   · 只有限品类 → 非入口类照样能出事，只是赔得起
-- 所以三样必须同时生效，缺一样另外两样都失效。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么策略挂在「档位」上而不是「商户」上
-- ─────────────────────────────────────────────────────────────────────────────
-- 挂商户 = 运营要逐个配，且改一次规则要批量刷数据。挂档位 = 三档三行，改规则改一行。
-- 这也与 S 轴锁定一致：商家类型就是 legal_form 的三个值，不再增删档位。
--
-- 不落 sys_setting 的理由与「费率建独立表」同理：规则要能回查
-- 「当初那单是按什么策略放行的」，而 KV 配置只存当前值。

CREATE TABLE IF NOT EXISTS mch_admission_policy
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    legal_form VARCHAR(24) NOT NULL COMMENT 'MICRO / INDIVIDUAL / ENTERPRISE，对应 S3 / S2 / S1',
    -- 金额一律用「分」，与 stl_bill、ord_order 保持同一口径，避免小数误差
    required_deposit_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '应缴保证金（分）；0 = 免缴',
    single_order_limit_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '单笔限额（分）；0 = 不限',
    daily_amount_limit_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '日累计限额（分）；0 = 不限',
    -- 小微拿不到经营许可证，本来就过不了资质校验；这一条是把它写成显式规则，
    -- 而不是依赖「他碰巧申请不下来」这个副作用
    ban_qualified_category TINYINT(4) NOT NULL DEFAULT 0 COMMENT '1 = 禁止经营任何「需资质」品类',
    banned_category_codes VARCHAR(1024) DEFAULT NULL COMMENT '额外禁售类目编码，JSON 数组',
    enabled TINYINT(4) NOT NULL DEFAULT 1 COMMENT '0 = 该档位不做任何限制',
    remark VARCHAR(255) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_admission_legal_form (legal_form, tenant_no)
) COMMENT='准入策略：按 legal_form 档位配置，不按商户配置';

-- 保证金账户。**不走微信资金通道，是平台自己记的账**：
-- 本期只回答「够不够」，实扣实退（理赔）是后续的事。
CREATE TABLE IF NOT EXISTS mch_deposit
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    merchant_no VARCHAR(64) NOT NULL,
    paid_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '实缴（分）',
    frozen_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '理赔占用中（分）',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mch_deposit_merchant (merchant_no, tenant_no)
) COMMENT='保证金账户，一商户一行';

-- 流水不是可选项：**只有余额字段的账户是不可审计的**。
-- 出现争议时，没有流水就说不清「这笔钱什么时候少的、谁扣的、凭什么扣」，
-- 而保证金恰恰是争议最集中的一笔钱。
CREATE TABLE IF NOT EXISTS mch_deposit_txn
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    txn_no VARCHAR(64) NOT NULL,
    merchant_no VARCHAR(64) NOT NULL,
    txn_type VARCHAR(16) NOT NULL
        COMMENT 'PAY 缴纳 / REFUND 退还 / FREEZE 冻结 / UNFREEZE 解冻 / DEDUCT 扣划',
    -- 有符号：扣划为负。存绝对值再靠 txn_type 推方向，等于把方向这件事重复表达两遍，
    -- 两处一旦不一致就没法判定谁对
    amount_minor BIGINT(20) NOT NULL COMMENT '变动额（分），可为负',
    balance_after_minor BIGINT(20) NOT NULL COMMENT '变动后实缴余额（分），对账用',
    reason VARCHAR(255) DEFAULT NULL,
    operator VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mch_deposit_txn_no (txn_no, tenant_no),
    KEY idx_mch_deposit_txn_merchant (merchant_no, tenant_no)
) COMMENT='保证金流水';

-- 默认策略：S1 / S2 三项全 0、不禁品类
-- → **现有商户行为与上线前完全一致**，本次变更对他们是无感的。
-- 只有 MICRO 一档带上限制，且具体数值由运营在 /ops/admission/policies 上调。
--
-- 不加 WHERE NOT EXISTS 防重：表就在本文件里新建，恒为空；
-- 而无 FROM 的 SELECT 在 MySQL 下本来也不合法（要写 FROM DUAL）。
--
-- MICRO 的数值是**初始建议值**，不是结论：保证金 2000 元、单笔 500 元、日累计 5000 元。
-- 真正的额度要等有成交数据后由运营调，这里给的是「不至于一上来就放开」的保守起点。
INSERT INTO mch_admission_policy
    (legal_form, required_deposit_minor, single_order_limit_minor, daily_amount_limit_minor,
     ban_qualified_category, banned_category_codes, enabled, remark,
     tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    ('ENTERPRISE', 0, 0, 0, 0, NULL, 1, 'S1：出事能追到有偿付能力的主体，不设限',
     'MAIN', NOW(), 'SYSTEM', NOW(), NULL, 0, 0),
    ('INDIVIDUAL', 0, 0, 0, 0, NULL, 1, 'S2：能追到人、赔付能力弱；先不设限，观察后再调',
     'MAIN', NOW(), 'SYSTEM', NOW(), NULL, 0, 0),
    ('MICRO', 200000, 50000, 500000, 1, NULL, 1,
     'S3：几乎追不到人，平台是唯一被追的一方；保证金+限额+限品类三样同时生效',
     'MAIN', NOW(), 'SYSTEM', NOW(), NULL, 0, 0);
