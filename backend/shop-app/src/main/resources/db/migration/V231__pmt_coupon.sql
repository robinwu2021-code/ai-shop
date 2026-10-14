-- 券的新模型（P4）：券模板 = 权益 × 门槛 × 范围 × 有效期 × 发放 × 核销 × 次数。
--
-- 为什么不在 mkt_coupon 上加列：那张表的 type/faceMinor/discountRate 是已上线算价
-- 与三端展示的输入，一边加列一边改语义，改到一半的那段时间里两套口径同时在跑。
-- 新表 + Port 实现换库，回退就是把实现切回去（旧表原样留着）。
--
-- ⚠️ 回退的代价要说清楚：切回旧实现之后，**这段时间里发到新表的用户券看不见了**。
-- 所以 P4 上线后要盯一周再做 P9 退场，中途回退必须同时给客服一份补发名单。
CREATE TABLE IF NOT EXISTS pmt_coupon
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    coupon_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL COMMENT '商家券的主体；平台券为空',
    funder VARCHAR(16) NOT NULL DEFAULT 'MERCHANT' COMMENT 'PLATFORM / MERCHANT —— 分账扣谁的钱',
    title VARCHAR(128) NOT NULL,
    benefit_mode VARCHAR(16) NOT NULL DEFAULT 'CASH' COMMENT 'CASH 现金 / PERCENT 折扣 / GIFT 兑换 / FREE_SHIP 免运费',
    benefit_value BIGINT(20) NOT NULL DEFAULT 0 COMMENT 'CASH 面额(分) / PERCENT 万分比(8500=八五折) / 其余 0',
    benefit_cap_minor BIGINT(20) DEFAULT NULL COMMENT '折扣封顶(分)。PERCENT 必填 —— 不封顶的敞口随订单金额无限放大',
    benefit_ref VARCHAR(64) DEFAULT NULL COMMENT 'GIFT 兑换哪件商品',
    min_amount_minor BIGINT(20) DEFAULT NULL COMMENT '金额门槛。空 = 不限',
    min_qty INT(11) DEFAULT NULL COMMENT '件数门槛。空 = 不限',
    scope_type VARCHAR(16) NOT NULL DEFAULT 'ALL' COMMENT 'ALL 全店 / STORE 指定门店 / CATEGORY 指定类目 / GOODS 指定商品',
    scope_desc VARCHAR(128) DEFAULT NULL COMMENT '展示文案。规则以 pmt_coupon_scope 为准，两者不符要在运营端标出来',
    validity_mode VARCHAR(16) NOT NULL DEFAULT 'ABSOLUTE' COMMENT 'ABSOLUTE 固定起止 / RELATIVE 领取后 N 天',
    start_at BIGINT(20) DEFAULT NULL,
    end_at BIGINT(20) DEFAULT NULL,
    valid_days INT(11) DEFAULT NULL COMMENT 'RELATIVE 时的天数',
    issue_mode VARCHAR(16) NOT NULL DEFAULT 'CENTER' COMMENT 'CENTER 领券中心 / TARGETED 定向发 / ACTIVITY 活动发 / CODE 发码',
    redeem_mode VARCHAR(16) NOT NULL DEFAULT 'ORDER' COMMENT 'ORDER 下单抵扣 / STORE_CODE 到店出示核销',
    times_total INT(11) NOT NULL DEFAULT 1 COMMENT '一张能用几次。1 = 一次性；N = 次卡（豆浆 5 杯）',
    total_count INT(11) DEFAULT NULL COMMENT '发行量。空 = 不限（仅 TARGETED 允许）',
    received_count INT(11) NOT NULL DEFAULT 0 COMMENT '并发计数器，不是缓存：防超发靠它在一条 UPDATE 里判',
    per_user_limit INT(11) NOT NULL DEFAULT 1,
    budget_minor BIGINT(20) DEFAULT NULL COMMENT '预算。建券时断言 budget >= total_count × 单张最大优惠',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / PAUSED 暂停发放（已领的不受影响）/ ENDED',
    archived_at DATETIME DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_coupon_no (coupon_no),
    KEY idx_pmt_coupon_entity (entity_no, status),
    KEY idx_pmt_coupon_center (status, issue_mode, end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='券模板：权益 × 门槛 × 范围 × 有效期 × 发放 × 核销 × 次数';

-- 范围是**规则**，不是文案。老模型只有 scope_desc「仅限粮油类」这句话，
-- 而校验只看 entity_no —— 买猫粮照样能用，商家会认为是算错了钱。
CREATE TABLE IF NOT EXISTS pmt_coupon_scope
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    coupon_no VARCHAR(64) NOT NULL,
    scope_type VARCHAR(16) NOT NULL COMMENT 'STORE / CATEGORY / GOODS',
    ref_no VARCHAR(64) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_coupon_scope (tenant_no, coupon_no, scope_type, ref_no),
    KEY idx_pmt_scope_ref (scope_type, ref_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='券的适用范围（规则）。scope_desc 只是文案';

-- 发到某个人手上的那一张。**有效期落在这一行上**：
-- RELATIVE 券领取时就算好 expire_at，现算的话改一次模板会把已发出去的券一起改掉。
CREATE TABLE IF NOT EXISTS pmt_user_coupon
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_coupon_no VARCHAR(64) NOT NULL,
    coupon_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL COMMENT '冗余：券包按店分组、商家看自己发出去多少',
    issue_no VARCHAR(64) DEFAULT NULL COMMENT '哪一批发的',
    status VARCHAR(16) NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED / USED（一次性用掉或次卡用满）/ EXPIRED / REVOKED',
    times_used INT(11) NOT NULL DEFAULT 0 COMMENT '次卡已核销几次',
    order_no VARCHAR(64) DEFAULT NULL COMMENT '线上抵扣占用它的那一单。取消订单按这一列退回',
    used_at BIGINT(20) DEFAULT NULL,
    received_at BIGINT(20) NOT NULL,
    expire_at BIGINT(20) NOT NULL COMMENT '这一张的失效时刻',
    redeem_code VARCHAR(32) DEFAULT NULL COMMENT '到店核销码。只有 STORE_CODE 券有',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_user_coupon_no (user_coupon_no),
    UNIQUE KEY uk_pmt_redeem_code (tenant_no, redeem_code),
    KEY idx_pmt_uc_user (user_no, status, expire_at),
    KEY idx_pmt_uc_coupon (coupon_no, status),
    KEY idx_pmt_uc_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='用户券：发到某个人手上的那一张，有自己的有效期';

-- 一批发放。**不静默少发**：跳过多少、为什么跳过，要能在界面上说出来。
CREATE TABLE IF NOT EXISTS pmt_coupon_issue
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    issue_no VARCHAR(64) NOT NULL,
    coupon_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL,
    issue_mode VARCHAR(16) NOT NULL COMMENT 'TARGETED 定向 / ACTIVITY 活动发 / CENTER 领券中心（自领的不记批次）',
    segment_no VARCHAR(64) DEFAULT NULL COMMENT '发给哪一群人',
    activity_no VARCHAR(64) DEFAULT NULL COMMENT '因哪场活动发的',
    rule_snapshot TEXT DEFAULT NULL COMMENT '发放当时的人群条件快照。人群条件后来会改，追责要看当时那一份',
    planned_count INT(11) NOT NULL DEFAULT 0,
    issued_count INT(11) NOT NULL DEFAULT 0,
    skipped_count INT(11) NOT NULL DEFAULT 0 COMMENT '被跳过的（已达每人上限、线索会员、已退订、券已发完）',
    skip_detail VARCHAR(255) DEFAULT NULL COMMENT '四类跳过各多少。界面要能说「12 跳过：9 人已达上限、3 人是线索」',
    amount_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '本批最大敞口',
    operator_no VARCHAR(64) DEFAULT NULL,
    issued_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_issue_no (issue_no),
    KEY idx_pmt_issue_coupon (coupon_no, issued_at),
    KEY idx_pmt_issue_segment (segment_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='发放批次：发给谁、发了多少、跳过多少、谁发的';

-- 优惠发生记录：**券的钱只在这一处记**。线上抵扣带 order_no，
-- 线下核销带 store_no + operator_no，次卡用 5 次就是 5 行。
CREATE TABLE IF NOT EXISTS pmt_apply
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    apply_no VARCHAR(64) NOT NULL,
    promo_type VARCHAR(16) NOT NULL COMMENT 'ACTIVITY 活动 / COUPON 券 / POINTS 积分',
    promo_no VARCHAR(64) NOT NULL COMMENT '活动号 / 用户券号 / 积分流水号',
    user_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL,
    store_no VARCHAR(64) DEFAULT NULL COMMENT '线下核销在哪家门店；线上单填出货门店',
    order_no VARCHAR(64) DEFAULT NULL COMMENT '线上抵扣用在哪一单。线下核销为空',
    sub_order_no VARCHAR(64) DEFAULT NULL COMMENT '按商家拆的那一单；跨商家的平台券会有多行',
    redeem_mode VARCHAR(16) NOT NULL DEFAULT 'ORDER' COMMENT 'ORDER 下单抵扣 / STORE_CODE 到店核销',
    operator_no VARCHAR(64) DEFAULT NULL COMMENT '线下核销时是哪个店员',
    amount_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '这一次减了多少。兑换类为 0',
    funder VARCHAR(16) NOT NULL DEFAULT 'MERCHANT' COMMENT 'PLATFORM / MERCHANT，与结算拆分同一口径',
    applied_at BIGINT(20) NOT NULL,
    reverted_at BIGINT(20) DEFAULT NULL COMMENT '订单取消/退款时置。线下核销不可撤销，那一行恒为空',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_apply_no (apply_no),
    KEY idx_pmt_apply_order (order_no),
    KEY idx_pmt_apply_promo (promo_type, promo_no, applied_at),
    KEY idx_pmt_apply_entity (entity_no, applied_at),
    KEY idx_pmt_apply_store (store_no, applied_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='优惠发生记录：一单命中了什么、一张券被用了几次，线上线下同一张表';
