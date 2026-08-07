-- M10.2 补齐三个**整域缺表**的领域对象（待办 T-6 / T-7 / T-8）。
--
-- 与 V15 的区别：V15 是「表在、缺几列」，这里是「整块功能没有存储」——
-- 三端都已按契约实现并跑在 mock 上，库里一张表都没有。

-- ═══════════════════════════════════════════ T-6 评价域
--
-- 评价是**三端都已实现**的功能：C 端写评与看评、B 端回复与申诉、平台端 P-13.1 评价治理。
-- 此前库里没有任何评价表，接后端时这条链路整条落不了地。
--
-- 隐藏后果：usr_merchant 的 rating / rating_count / score_goods / score_service / score_speed
-- 这些聚合列**没有数据来源** —— 在此之前它们只能是假数据。

CREATE TABLE IF NOT EXISTS rvw_review
(
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    review_no         VARCHAR(64)  NOT NULL,
    -- 一单一评：唯一键落在 (sub_order_no, goods_no) 上而不是 review_no 之外再加约束，
    -- 因为一个子单可能有多个商品，每个商品各评一次
    sub_order_no      VARCHAR(64)  NOT NULL,
    order_no          VARCHAR(64)  NOT NULL,
    goods_no          VARCHAR(64)  NOT NULL,
    sku_no            VARCHAR(64)   NULL,
    merchant_no       VARCHAR(64)  NOT NULL,
    user_no           VARCHAR(64)  NOT NULL,
    -- 昵称与头像存快照：用户改昵称不该让历史评价跟着变
    nickname          VARCHAR(64)   NULL,
    avatar            VARCHAR(512)  NULL,
    rating            TINYINT      NOT NULL COMMENT '总分 1-5',
    -- 三维分（B-9.3 / P-13.1.4）。总分仍保留：老数据没有维度分，列表页也只显示一个星级；
    -- 维度分用于评分算法与商家诊断 ——「货好但送得慢」这种问题，只看总分永远看不出来
    score_goods       TINYINT       NULL COMMENT '商品本身 1-5',
    score_fulfillment TINYINT       NULL COMMENT '履约（快慢/包装/缺损）1-5',
    score_service     TINYINT       NULL COMMENT '服务（沟通/售后态度）1-5',
    content           VARCHAR(1024) NULL,
    images            JSON          NULL,
    spec              VARCHAR(255)  NULL COMMENT '购买规格快照：让人知道这条评价说的是哪个 SKU',
    like_count        INT          NOT NULL DEFAULT 0,
    reply             VARCHAR(512)  NULL COMMENT '商家回复；一条评价只能回一次',
    replied_at        BIGINT        NULL,
    -- 平台侧审核态。C 端只看得到 PASSED
    status            VARCHAR(16)  NOT NULL DEFAULT 'PASSED' COMMENT 'PENDING/PASSED/REJECTED',
    reject_reason     VARCHAR(255)  NULL COMMENT '驳回原因：与门店审核同一条规矩 —— 驳回必须写清楚',
    -- 刷评信号（P-13.1.5）。**是线索不是结论**，命中不等于判定，所以存原始命中项而不是分值
    risk_flags        JSON          NULL COMMENT 'SAME_DEVICE/SAME_IP/TEXT_DUP/BURST：给人审的线索',
    tenant_no         VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at        DATETIME     NOT NULL,
    created_by        VARCHAR(64)   NULL,
    updated_at        DATETIME     NOT NULL,
    updated_by        VARCHAR(64)   NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    deleted           TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_review_no (review_no),
    -- 一单一商品一评：靠库约束挡住重复提交，不指望应用层记得判
    UNIQUE KEY uk_order_goods (sub_order_no, goods_no),
    KEY idx_goods_status (goods_no, status),
    KEY idx_merchant_status (merchant_no, status),
    KEY idx_user (user_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商品评价（含三维分）';

-- 商家对差评的申诉。**这是唯一能把差评送进平台裁决台的入口** ——
-- 平台端 P-13.1.3 的裁决页早就建好，此前没有单据表，那张台子收不到任何单。
CREATE TABLE IF NOT EXISTS rvw_appeal
(
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    appeal_no    VARCHAR(64)  NOT NULL,
    review_no    VARCHAR(64)  NOT NULL,
    merchant_no  VARCHAR(64)  NOT NULL,
    reason       VARCHAR(512) NOT NULL COMMENT '申诉理由，商家填',
    images       JSON          NULL COMMENT '举证图：聊天记录、物流截图',
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/UPHELD(差评下架)/REJECTED(评价保留)',
    submitted_at BIGINT       NOT NULL,
    -- 无论成立还是驳回都必须写 —— 商家会看到，「已读不处理」不是一种结果
    verdict      VARCHAR(512)  NULL COMMENT '裁决说明：成立与驳回都必须写',
    decided_at   BIGINT        NULL,
    decided_by   VARCHAR(64)   NULL,
    tenant_no    VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME     NOT NULL,
    created_by   VARCHAR(64)   NULL,
    updated_at   DATETIME     NOT NULL,
    updated_by   VARCHAR(64)   NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_appeal_no (appeal_no),
    -- 一条评价只能申诉一次：申诉被驳回后再申诉一次，等于把裁决当抽奖
    UNIQUE KEY uk_review (review_no),
    KEY idx_status (status, submitted_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商家对差评的申诉（P-13.1.3 裁决入口）';

-- 点赞明细。
-- 不建这张表的话，`Review.likeCount` 只是个可以随便改的数字，
-- 而契约里的 `Review.liked`（当前用户是否点过赞）**根本算不出来** —— 页面只能永远显示未点赞。
CREATE TABLE IF NOT EXISTS rvw_review_like
(
    id         BIGINT      AUTO_INCREMENT PRIMARY KEY,
    review_no  VARCHAR(64) NOT NULL,
    user_no    VARCHAR(64) NOT NULL,
    tenant_no  VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME    NOT NULL,
    created_by VARCHAR(64)  NULL,
    updated_at DATETIME    NOT NULL,
    updated_by VARCHAR(64)  NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    deleted    TINYINT     NOT NULL DEFAULT 0,
    -- 一人一评一次：靠库约束挡重复点赞，不指望前端禁用按钮
    UNIQUE KEY uk_review_user (review_no, user_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '评价点赞明细（likeCount 的真源）';

-- ═══════════════════════════════════════════ T-7 商家营销活动
--
-- 库里此前只有平台侧的 mkt_coupon，没有商家活动表。
-- 契约把四类活动（店铺券/满减/限时特价/买赠）刻意**统一成一个类型**：
-- 它们在数据上只差「触发条件 + 优惠方式」，各建一套的结果是四份几乎一样的增删改查，
-- 以及四份互不知情的叠加规则 —— 而叠加恰恰是最容易算错的地方。建表保持这个统一。

CREATE TABLE IF NOT EXISTS mkt_campaign
(
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    campaign_no       VARCHAR(64)  NOT NULL,
    merchant_no       VARCHAR(64)  NOT NULL COMMENT '活动是店铺级的，不跨店',
    type              VARCHAR(16)  NOT NULL COMMENT 'COUPON/FULL_CUT/FLASH/BUY_GIFT —— 决定下面哪几列有意义',
    name              VARCHAR(128) NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/RUNNING/PAUSED/ENDED',
    start_at          BIGINT       NOT NULL,
    end_at            BIGINT       NOT NULL,
    threshold_minor   BIGINT        NULL COMMENT 'COUPON/FULL_CUT：门槛（分）',
    discount_minor    BIGINT        NULL COMMENT 'COUPON/FULL_CUT：优惠额（分）',
    flash_price_minor BIGINT        NULL COMMENT 'FLASH：活动价（分）',
    buy_n             INT           NULL COMMENT 'BUY_GIFT：购买件数门槛',
    gift_m            INT           NULL COMMENT 'BUY_GIFT：赠送件数',
    goods_nos         JSON          NULL COMMENT '参与商品；空 = 全店',
    -- 预算上限，防止发着发着超支。**必须在服务端校验** —— 客服也持有发券权限
    total_count       INT           NULL COMMENT 'COUPON：发放总量；NULL = 不限量',
    taken_count       INT          NOT NULL DEFAULT 0 COMMENT 'COUPON：已领取数',
    used_count        INT          NOT NULL DEFAULT 0 COMMENT '已核销/已使用次数，衡量效果',
    tenant_no         VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at        DATETIME     NOT NULL,
    created_by        VARCHAR(64)   NULL,
    updated_at        DATETIME     NOT NULL,
    updated_by        VARCHAR(64)   NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    deleted           TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_campaign_no (campaign_no),
    KEY idx_merchant_status (merchant_no, status),
    -- 生效中的活动按时间窗扫描：结算价格时要问「此刻有哪些活动在跑」
    KEY idx_status_window (status, start_at, end_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商家营销活动（券/满减/限时特价/买赠统一模型）';

-- ═══════════════════════════════════════════ T-8 规格模板
--
-- B 端录商品时要用。平台维护一批通用模板，商家可以自存 —— 商家只能改自己的。

CREATE TABLE IF NOT EXISTS prd_spec_template
(
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    template_no   VARCHAR(64)  NOT NULL,
    scope         VARCHAR(16)  NOT NULL DEFAULT 'MERCHANT' COMMENT 'PLATFORM(平台统一维护)/MERCHANT(商家自存)',
    category_type VARCHAR(16)   NULL COMMENT '平台模板按类目推荐；商家模板不限类目',
    name          VARCHAR(64)  NOT NULL COMMENT '规格维度名，如「重量」「香型」',
    -- 选项存 JSON 而不是拆行：模板的选项是**整体替换**的（改模板就是改一整组），
    -- 拆成子表后每次保存都要 diff 出增删改，没有收益
    options       JSON         NOT NULL COMMENT '[{code,label}]；来自平台模板的有 code，手输的没有',
    merchant_no   VARCHAR(64)   NULL COMMENT 'scope=MERCHANT 时归属的商家',
    tenant_no     VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME     NOT NULL,
    created_by    VARCHAR(64)   NULL,
    updated_at    DATETIME     NOT NULL,
    updated_by    VARCHAR(64)   NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_template_no (template_no),
    KEY idx_scope_category (scope, category_type),
    KEY idx_merchant (merchant_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '规格模板（平台维护 + 商家自存）';
