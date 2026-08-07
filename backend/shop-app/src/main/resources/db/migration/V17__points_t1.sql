-- T-1 积分域。V15 显式把它推迟到单独一版：账户表与流水表必须一起建，
-- 拆着上会出现「有账户没流水」的中间态。设计见 docs/technical/积分域-需求与数据库设计.md。
--
-- 两条贯穿全域的口径，看表结构前先记住：
--   ① 商家有**两本账**：额度（分，发放即占用）与资金（钱，兑付才发生）。
--      合成一个余额会让同一笔分占两次额度、同一笔收入拿两遍好处。
--   ② 跨商家兑付按**批次**拆行（pts_redeem_alloc）：用户一次用 3000 分，
--      可能 2000 是 A 发的、1000 是 B 发的，不拆就算不出谁欠谁。

-- ---------------------------------------------------------------------------
-- 用户侧
-- ---------------------------------------------------------------------------

-- 账户表是**派生**的，真源是流水（ADR-006 §3.4）。建它只为两件事：
--   ① 并发锁行 ② 避免每次下单对全量流水求和
-- 对账任务每日用流水重算校验，不一致以流水为准并告警。
CREATE TABLE IF NOT EXISTS pts_user_account
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_no    VARCHAR(64) NOT NULL,
    balance    BIGINT      NOT NULL DEFAULT 0 COMMENT '可用余额（派生，以流水为准）',
    total_earn BIGINT      NOT NULL DEFAULT 0,
    total_use  BIGINT      NOT NULL DEFAULT 0,
    tenant_no  VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME    NOT NULL,
    created_by VARCHAR(64)  NULL,
    updated_at DATETIME    NOT NULL,
    updated_by VARCHAR(64)  NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    deleted    TINYINT     NOT NULL DEFAULT 0,
    UNIQUE KEY uk_pts_acc_user (user_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户积分账户（锁行 + 派生余额）';

-- EARN 行同时是**批次行**：只有它带 expire_at 与 remaining。
-- 消耗按「先到期先用」而非单纯先进先出 —— 有效期相同时两者一致，
-- 一旦出现不同有效期的活动分，先到期先用对用户更有利，客诉最少。
CREATE TABLE IF NOT EXISTS pts_user_ledger
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    ledger_no      VARCHAR(64) NOT NULL,
    user_no        VARCHAR(64) NOT NULL,
    biz_type       VARCHAR(16) NOT NULL COMMENT 'EARN/USE/REFUND/EXPIRE',
    points         BIGINT      NOT NULL COMMENT '带符号：EARN/REFUND 为正，USE/EXPIRE 为负',
    balance_after  BIGINT      NOT NULL COMMENT '快照，用于定位「从哪条开始错的」',
    remaining      BIGINT       NULL COMMENT '仅 EARN：该批次剩余可用，消耗时递减',
    expire_at      BIGINT       NULL COMMENT '仅 EARN：到期时间',
    issuer_merchant_no VARCHAR(64) NULL COMMENT '仅 EARN：发放方；平台自发填 PLATFORM',
    sub_order_no   VARCHAR(64)  NULL,
    remark         VARCHAR(255) NULL,
    tenant_no      VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at     DATETIME    NOT NULL,
    created_by     VARCHAR(64)  NULL,
    updated_at     DATETIME    NOT NULL,
    updated_by     VARCHAR(64)  NULL,
    version        BIGINT      NOT NULL DEFAULT 0,
    deleted        TINYINT     NOT NULL DEFAULT 0,
    UNIQUE KEY uk_pts_ledger_no (ledger_no),
    -- 发放幂等**不放在这里**：本想用 (sub_order_no, biz_type) 唯一，
    -- 但部分退款会产生多条 REFUND 行，那个唯一键会误伤。
    -- 幂等由 ord_sub_order.points_granted 标记保证（R1.7）。
    KEY idx_pts_ledger_user (user_no, id),
    KEY idx_pts_ledger_sub_order (sub_order_no, biz_type),
    -- FIFO 取批次：按到期时间升序找 remaining > 0 的 EARN 行
    KEY idx_pts_batch (user_no, biz_type, expire_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户积分流水（真源；EARN 行即批次）';

-- ---------------------------------------------------------------------------
-- 跨商家清算的事实表
-- ---------------------------------------------------------------------------

-- 一条 USE 流水拆成多条 alloc，每条对应一个被消耗的批次（= 一个发放商家）。
--
-- 状态机解决时点问题：兑付在子单 COMPLETED 时才成立，此前只是预占。
--   下单扣分 → PENDING（不进账期单）
--   COMPLETED → CONFIRMED（此刻才落 period，计入账期）
--   取消/退款 → REVERSED（兑付未发生过）
--
-- 退款返还要靠本表**还回原批次** —— 若新开一个 365 天的批次，
-- 用户就能靠「下单 → 退款」无限续期积分。
CREATE TABLE IF NOT EXISTS pts_redeem_alloc
(
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    alloc_no             VARCHAR(64) NOT NULL,
    use_ledger_no        VARCHAR(64) NOT NULL COMMENT '用户的 USE 流水',
    earn_ledger_no       VARCHAR(64) NOT NULL COMMENT '被消耗的 EARN 批次',
    user_no              VARCHAR(64) NOT NULL,
    sub_order_no         VARCHAR(64) NOT NULL,
    issuer_merchant_no   VARCHAR(64) NOT NULL COMMENT '发放方；平台自发为 PLATFORM，成本走池子',
    acceptor_merchant_no VARCHAR(64) NOT NULL COMMENT '收单方',
    points               BIGINT      NOT NULL,
    amount_minor         BIGINT      NOT NULL COMMENT '折算金额（分）',
    rate_snapshot        INT         NOT NULL COMMENT '汇率快照（多少分 = 1 元）—— 调汇率不影响已发生的账',
    self_used            TINYINT     NOT NULL DEFAULT 0 COMMENT '自发自用：直接冲抵，不进账期单',
    status               VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CONFIRMED/REVERSED',
    period               VARCHAR(8)   NULL COMMENT '账期 YYYYMM，CONFIRMED 时落定',
    confirmed_at         BIGINT       NULL,
    tenant_no            VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at           DATETIME    NOT NULL,
    created_by           VARCHAR(64)  NULL,
    updated_at           DATETIME    NOT NULL,
    updated_by           VARCHAR(64)  NULL,
    version              BIGINT      NOT NULL DEFAULT 0,
    deleted              TINYINT     NOT NULL DEFAULT 0,
    UNIQUE KEY uk_pts_alloc_no (alloc_no),
    -- 出账期单时两个方向各扫一次，这是本表唯一的高频批量查询
    KEY idx_pts_alloc_issuer (period, status, issuer_merchant_no),
    KEY idx_pts_alloc_acceptor (period, status, acceptor_merchant_no),
    KEY idx_pts_alloc_use (use_ledger_no),
    KEY idx_pts_alloc_sub_order (sub_order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '积分使用分摊 / 兑付明细（跨商家清算事实表）';

-- ---------------------------------------------------------------------------
-- 商家侧：额度台账 + 资金流水
-- ---------------------------------------------------------------------------

-- available = credit_limit - used，**不存列** —— 两处存迟早不一致。
-- 发放校验走 UPDATE ... WHERE used + ? <= credit_limit，用影响行数判断，
-- 不用「先查后扣」：后者在并发下必然超发。
CREATE TABLE IF NOT EXISTS pts_merchant_quota
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_no  VARCHAR(64) NOT NULL,
    credit_limit BIGINT      NOT NULL DEFAULT 0 COMMENT '授信额度（分）= min(固定上限, 近3期均货款×系数)，P12',
    used         BIGINT      NOT NULL DEFAULT 0 COMMENT '已发放未兑付未过期。兑付不再扣、收单不增加',
    suspended    TINYINT     NOT NULL DEFAULT 0 COMMENT '超授信自动停发；按发收对称同时停收',
    suspended_at BIGINT       NULL,
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    created_by   VARCHAR(64)  NULL,
    updated_at   DATETIME    NOT NULL,
    updated_by   VARCHAR(64)  NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    UNIQUE KEY uk_pts_quota_merchant (merchant_no),
    -- 平台端「授信风险榜」：按占用率排序 = 按跑路风险排序
    KEY idx_pts_quota_used (suspended, used)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商家积分额度台账（授信 / 占用）';

-- 额度列与金额列**并存且不合并**：合并后商家一定会问
-- 「为什么发了 300 分只扣 3 块又没扣」。
CREATE TABLE IF NOT EXISTS pts_merchant_ledger
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    ledger_no          VARCHAR(64) NOT NULL,
    merchant_no        VARCHAR(64) NOT NULL,
    biz_type           VARCHAR(16) NOT NULL COMMENT 'ISSUE/REDEEM_IN/REDEEM_OUT/EXPIRE_BACK/REVOKE/SETTLE',
    quota_delta        BIGINT      NOT NULL DEFAULT 0 COMMENT '额度变动（分）：仅 ISSUE/EXPIRE_BACK/REVOKE 非零',
    amount_delta_minor BIGINT      NOT NULL DEFAULT 0 COMMENT '金额变动（分）：仅 REDEEM_IN/REDEEM_OUT/SETTLE 非零',
    quota_used_after   BIGINT       NULL COMMENT '额度占用快照',
    counterparty_no    VARCHAR(64)  NULL COMMENT '对手方商家 —— 「收到积分」要写明是谁发的，这是方案的价值所在',
    sub_order_no       VARCHAR(64)  NULL,
    alloc_no           VARCHAR(64)  NULL,
    period             VARCHAR(8)   NULL,
    remark             VARCHAR(255) NULL,
    tenant_no          VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at         DATETIME    NOT NULL,
    created_by         VARCHAR(64)  NULL,
    updated_at         DATETIME    NOT NULL,
    updated_by         VARCHAR(64)  NULL,
    version            BIGINT      NOT NULL DEFAULT 0,
    deleted            TINYINT     NOT NULL DEFAULT 0,
    UNIQUE KEY uk_pts_m_ledger_no (ledger_no),
    KEY idx_pts_m_ledger_merchant (merchant_no, id),
    KEY idx_pts_m_ledger_period (merchant_no, period, biz_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商家积分流水（额度 + 金额双口径）';

-- ---------------------------------------------------------------------------
-- 结算侧
-- ---------------------------------------------------------------------------

-- 货款按订单即时结算（stl_bill），积分按账期结算（本表），两条线并行。
-- 兑付时点晚于 stl_bill 生成时点，塞不进去 —— 这是 v1 方案的错误所在。
CREATE TABLE IF NOT EXISTS stl_points_bill
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    bill_no       VARCHAR(64) NOT NULL,
    merchant_no   VARCHAR(64) NOT NULL,
    period        VARCHAR(8)  NOT NULL COMMENT '账期 YYYYMM',
    income_minor  BIGINT      NOT NULL DEFAULT 0 COMMENT 'Σ REDEEM_IN：别人的分在我这儿被花掉',
    expense_minor BIGINT      NOT NULL DEFAULT 0 COMMENT 'Σ REDEEM_OUT：我发的分在别人那儿被花掉',
    net_minor     BIGINT      NOT NULL DEFAULT 0 COMMENT '>0 平台付给商家；<0 从货款扣',
    alloc_count   INT         NOT NULL DEFAULT 0,
    status        VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/SETTLED',
    settled_at    BIGINT       NULL,
    tenant_no     VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME    NOT NULL,
    created_by    VARCHAR(64)  NULL,
    updated_at    DATETIME    NOT NULL,
    updated_by    VARCHAR(64)  NULL,
    version       BIGINT      NOT NULL DEFAULT 0,
    deleted       TINYINT     NOT NULL DEFAULT 0,
    UNIQUE KEY uk_pts_bill_no (bill_no),
    -- 一期一张：重复生成 = 重复收付
    UNIQUE KEY uk_pts_bill_merchant_period (merchant_no, period),
    KEY idx_pts_bill_period_status (period, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商家积分账期结算单';

-- 平台清算备付账户 —— **不是兑付资金池**。
-- 资金只来自商家货款轧差，平台不承诺兑付、不接受充值。定性错了是牌照问题：
-- 跨商户通用 + 可兑付 ≈ 多用途预付卡，需支付业务许可。
--
-- 每期校验 Σ stl_points_bill.net_minor + 本期池子净额 = 0，不等即坏账敞口。
CREATE TABLE IF NOT EXISTS stl_points_pool
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    flow_no      VARCHAR(64) NOT NULL,
    direction    VARCHAR(8)  NOT NULL COMMENT 'IN/OUT',
    pool_type    VARCHAR(24) NOT NULL COMMENT 'MERCHANT_PAY/RECOVERY/PENALTY/MERCHANT_RECEIVE/PLATFORM_ISSUE/BAD_DEBT',
    amount_minor BIGINT      NOT NULL,
    balance_after BIGINT     NOT NULL,
    merchant_no  VARCHAR(64)  NULL,
    period       VARCHAR(8)   NULL,
    ref_no       VARCHAR(64)  NULL COMMENT '关联账期单 / 兑付明细',
    remark       VARCHAR(255) NULL,
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    created_by   VARCHAR(64)  NULL,
    updated_at   DATETIME    NOT NULL,
    updated_by   VARCHAR(64)  NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    UNIQUE KEY uk_pts_pool_flow_no (flow_no),
    KEY idx_pts_pool_period (period, pool_type),
    KEY idx_pts_pool_merchant (merchant_no, period)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '平台积分清算备付账户流水';

-- ---------------------------------------------------------------------------
-- 三级灰度开关 + 商品发放配置 + 订单抵扣快照
-- ---------------------------------------------------------------------------

-- L1 全局开关走 sys_config（FEATURES.points），不占列。
ALTER TABLE cmt_community ADD COLUMN points_enabled TINYINT NOT NULL DEFAULT 0 COMMENT 'L2 社区级灰度';

-- 一个开关同时决定「能发」与「能收」：不提供拆分配置。
-- 允许只收不发的话，理性商家全会这么选，于是没人发分，积分归零。
ALTER TABLE usr_merchant ADD COLUMN points_enabled TINYINT NOT NULL DEFAULT 0 COMMENT 'L3 商家级：canIssue == canAccept，同一个开关';
ALTER TABLE usr_merchant ADD COLUMN points_forced TINYINT NOT NULL DEFAULT 0 COMMENT '按行业强制开，商家不可自行关闭（需提前30天通知 + 费率补偿 + 申诉通道）';

ALTER TABLE prd_goods ADD COLUMN points_config INT NULL COMMENT '本商品发放积分数；NULL 走成交额兜底比例。赠品行不发';

-- 结算与售后都要读本单的积分抵扣，不能每次回溯 alloc 表。
ALTER TABLE ord_sub_order ADD COLUMN points_deduct INT NOT NULL DEFAULT 0 COMMENT '本单抵扣的积分数';
ALTER TABLE ord_sub_order ADD COLUMN points_deduct_minor BIGINT NOT NULL DEFAULT 0 COMMENT '本单积分抵扣金额（分）。上限 = 券后金额 30%，运费不参与';
ALTER TABLE ord_sub_order ADD COLUMN points_granted TINYINT NOT NULL DEFAULT 0 COMMENT '发放幂等标记：防重复核销重复发分';
