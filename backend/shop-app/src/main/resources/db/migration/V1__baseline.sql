-- ═══════════════════════════════════════════════════════════════════════════
-- 全库基准 Schema（V1，2026-08-09 全量重建）
--
-- 由 46 个历史迁移收敛而来，同时落地《全域命名基准》：
--   · merchant 一词只用于支付语境：经营实体 = mch_entity（键 entity_no）
--   · 商家经营域独立前缀 mch_：entity / payment_merchant / store / account / store_role
--   · 主体类型（小微/个体户/企业）= 法律形态 sys_legal_form（列 legal_form）
--   · 平台运营 = sys_ops_staff（"staff" 从此只有一个含义）
--
-- 与命名基准的两处偏离（结构未到位，名字不抢跑）：
--   · mch_entity_community 仍按主体挂（M5 下沉到门店后更名 mch_store_community）
--   · prd_community_pool 保留（M3 由 选品×服务区域 取代后删除）
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS cmt_community
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    community_no VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    address VARCHAR(255) DEFAULT NULL,
    lat_e6 INT(11) DEFAULT NULL,
    lng_e6 INT(11) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    city_code VARCHAR(32) DEFAULT NULL COMMENT '所属城市编码：scope=CITY 的商家靠它判定可达',
    points_enabled TINYINT(4) NOT NULL DEFAULT 0 COMMENT 'L2 社区级灰度',
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_no (community_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='社区';

CREATE TABLE IF NOT EXISTS cmt_pickup_point
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    pickup_no VARCHAR(64) NOT NULL,
    community_no VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    address VARCHAR(255) DEFAULT NULL,
    lat_e6 INT(11) DEFAULT NULL,
    lng_e6 INT(11) DEFAULT NULL,
    type VARCHAR(16) NOT NULL DEFAULT 'STORE' COMMENT 'STORE=商家自有门店(不收费) / NEIGHBOR=邻居家(零报酬) / PLATFORM=平台提供(线下协商费率)',
    scope VARCHAR(16) NOT NULL DEFAULT 'PERMANENT' COMMENT 'PERMANENT/GROUP_INSTANCE',
    owner_ref VARCHAR(64) DEFAULT NULL COMMENT 'STORE=entity_no / NEIGHBOR=user_no / PLATFORM=NULL',
    group_no VARCHAR(64) DEFAULT NULL,
    open_hours VARCHAR(64) DEFAULT NULL,
    arrival_desc VARCHAR(128) DEFAULT NULL,
    service_fee_rate INT(11) NOT NULL DEFAULT 0 COMMENT '万分比；NEIGHBOR 必须为 0',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    time_slot VARCHAR(64) DEFAULT NULL COMMENT '约定取货时段：邻居家不能一直堆着货（B15）',
    service_fee_per_item_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '按件履约服务费（分）：邻里自提必须为 0，否则承接的邻居就是团长',
    fee_mode VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PER_ITEM/RATE：用哪一种计费口径。STORE 与 NEIGHBOR 恒为 NONE',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pickup_no (pickup_no),
    KEY idx_community (community_no),
    KEY idx_owner (owner_ref),
    CONSTRAINT ck_neighbor_zero_fee CHECK (type <> 'NEIGHBOR' or service_fee_rate = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='自提点（ADR-005）';

CREATE TABLE IF NOT EXISTS ful_batch
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    batch_no VARCHAR(64) NOT NULL,
    pickup_no VARCHAR(64) NOT NULL,
    arrive_date VARCHAR(16) NOT NULL COMMENT 'YYYY-MM-DD，按天分批',
    total_qty INT(11) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RECEIVED',
    received_at BIGINT(20) DEFAULT NULL,
    received_by VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_no (batch_no),
    KEY idx_pickup_date (pickup_no,arrive_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='到货批次';

CREATE TABLE IF NOT EXISTS ful_group_pickup
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    pickup_no VARCHAR(64) NOT NULL,
    group_no VARCHAR(64) NOT NULL COMMENT '所属团。作用域就是它 —— 拿别团的码来核销必须被拒',
    user_no VARCHAR(64) NOT NULL COMMENT '承接人 = 团发起人本人，不能是别人',
    name VARCHAR(128) NOT NULL COMMENT '如「3 幢老王家」',
    address VARCHAR(255) NOT NULL COMMENT '完整地址；对未付款用户按 B13 脱敏后下发',
    time_slot VARCHAR(64) DEFAULT NULL COMMENT '约定取货时段：邻居家不能一直堆着货（B15）',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/CLOSED（随团结束）',
    received_at BIGINT(20) DEFAULT NULL COMMENT '批次签收时间；未签收前不允许核销',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_pickup_no (pickup_no),
    UNIQUE KEY uk_group (group_no),
    KEY idx_user (user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='邻里自提点（团粒度临时点，随团生灭，零报酬）';

CREATE TABLE IF NOT EXISTS ful_verify_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    sub_order_no VARCHAR(64) DEFAULT NULL,
    pickup_no VARCHAR(64) NOT NULL,
    verify_code VARCHAR(16) NOT NULL,
    verify_type VARCHAR(16) NOT NULL DEFAULT 'SCAN',
    operator_no VARCHAR(64) NOT NULL COMMENT '操作人 userNo —— 代核销必须能追到人',
    result VARCHAR(24) NOT NULL COMMENT 'SUCCESS 或失败原因',
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_pickup_at (pickup_no,at),
    KEY idx_sub_order (sub_order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='核销日志（append-only）';

CREATE TABLE IF NOT EXISTS mkt_attribution
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL COMMENT '店铺码归因命中的商家',
    inviter_no VARCHAR(64) DEFAULT NULL,
    channel VARCHAR(64) DEFAULT NULL,
    source VARCHAR(16) NOT NULL,
    expire_at BIGINT(20) NOT NULL COMMENT '窗口期结束，默认 30 天（B1）',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user (user_no),
    KEY idx_entity (entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='归因关系（当前）';

CREATE TABLE IF NOT EXISTS mkt_attribution_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL,
    inviter_no VARCHAR(64) DEFAULT NULL,
    channel VARCHAR(64) DEFAULT NULL,
    source VARCHAR(16) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    prev_source VARCHAR(16) DEFAULT NULL,
    prev_ref VARCHAR(64) DEFAULT NULL COMMENT '被覆盖前的归属对象',
    reason VARCHAR(128) DEFAULT NULL COMMENT '判定依据，人可读',
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user_at (user_no,at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='归因判定留痕（append-only，可回放）';

CREATE TABLE IF NOT EXISTS mkt_campaign
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    campaign_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL COMMENT '活动是店铺级的，不跨店',
    type VARCHAR(16) NOT NULL COMMENT 'COUPON/FULL_CUT/FLASH/BUY_GIFT —— 决定下面哪几列有意义',
    name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/RUNNING/PAUSED/ENDED',
    start_at BIGINT(20) NOT NULL,
    end_at BIGINT(20) NOT NULL,
    threshold_minor BIGINT(20) DEFAULT NULL COMMENT 'COUPON/FULL_CUT：门槛（分）',
    discount_minor BIGINT(20) DEFAULT NULL COMMENT 'COUPON/FULL_CUT：优惠额（分）',
    flash_price_minor BIGINT(20) DEFAULT NULL COMMENT 'FLASH：活动价（分）',
    buy_n INT(11) DEFAULT NULL COMMENT 'BUY_GIFT：购买件数门槛',
    gift_m INT(11) DEFAULT NULL COMMENT 'BUY_GIFT：赠送件数',
    goods_nos JSON DEFAULT NULL COMMENT '参与商品；空 = 全店',
    total_count INT(11) DEFAULT NULL COMMENT 'COUPON：发放总量；NULL = 不限量',
    taken_count INT(11) NOT NULL DEFAULT 0 COMMENT 'COUPON：已领取数',
    used_count INT(11) NOT NULL DEFAULT 0 COMMENT '已核销/已使用次数，衡量效果',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_no (campaign_no),
    KEY idx_entity_status (entity_no,status),
    KEY idx_status_window (status,start_at,end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='商家营销活动（券/满减/限时特价/买赠统一模型）';

CREATE TABLE IF NOT EXISTS mkt_coupon
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    coupon_no VARCHAR(64) NOT NULL,
    title VARCHAR(128) NOT NULL,
    type VARCHAR(16) NOT NULL COMMENT 'FULL_CUT 满减 / DISCOUNT 折扣',
    face_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '满减面额',
    discount_rate INT(11) NOT NULL DEFAULT 0 COMMENT '折扣 ×100，88 = 8.8 折',
    threshold_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '使用门槛（商品额）',
    max_discount_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '折扣券封顶，0 = 不封顶',
    funder VARCHAR(16) NOT NULL DEFAULT 'PLATFORM',
    entity_no VARCHAR(64) DEFAULT NULL COMMENT '商家券限本店；平台券为空',
    total_count INT(11) NOT NULL DEFAULT 0 COMMENT '发行量',
    received_count INT(11) NOT NULL DEFAULT 0,
    per_user_limit INT(11) NOT NULL DEFAULT 1,
    start_at BIGINT(20) NOT NULL,
    end_at BIGINT(20) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    scope_desc VARCHAR(255) DEFAULT NULL COMMENT '适用范围文案（展示用，实际校验在服务端）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_no (coupon_no),
    KEY idx_status_time (status,start_at,end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='优惠券模板';

CREATE TABLE IF NOT EXISTS mkt_group_buy
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    group_no VARCHAR(64) NOT NULL,
    goods_no VARCHAR(64) NOT NULL,
    sku_no VARCHAR(64) DEFAULT NULL,
    entity_no VARCHAR(64) NOT NULL,
    title VARCHAR(255) DEFAULT NULL,
    cover VARCHAR(512) DEFAULT NULL,
    group_price_minor BIGINT(20) NOT NULL,
    origin_price_minor BIGINT(20) NOT NULL DEFAULT 0,
    min_count INT(11) NOT NULL DEFAULT 2 COMMENT '起团人数',
    joined_count INT(11) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/FORMED/FAILED/CLOSED',
    end_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    pickup_no VARCHAR(64) DEFAULT NULL COMMENT '成团范围：自提点。团购拼的是一车送到一个点的成本',
    initiator_user_no VARCHAR(64) DEFAULT NULL COMMENT 'C 端发起人；为空表示商家开的团。决定 isOwner 与「我发起的团」',
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_no (group_no),
    KEY idx_status_end (status,end_at),
    KEY idx_group_pickup_status (pickup_no,status),
    KEY idx_group_initiator (initiator_user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='商家团';

CREATE TABLE IF NOT EXISTS mkt_group_member
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    group_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    nickname VARCHAR(64) DEFAULT NULL,
    joined_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_user (group_no,user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='参团成员';

CREATE TABLE IF NOT EXISTS mkt_quote
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    quote_no VARCHAR(64) NOT NULL,
    request_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    unit_price_minor BIGINT(20) NOT NULL,
    min_qty INT(11) NOT NULL DEFAULT 1 COMMENT '起订量',
    note VARCHAR(512) DEFAULT NULL,
    valid_until BIGINT(20) NOT NULL,
    revision_count INT(11) NOT NULL DEFAULT 0,
    chosen TINYINT(4) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/WITHDRAWN',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quote_no (quote_no),
    UNIQUE KEY uk_request_entity (request_no,entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='商家报价';

CREATE TABLE IF NOT EXISTS mkt_quote_revision
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    quote_no VARCHAR(64) NOT NULL,
    request_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    from_price_minor BIGINT(20) NOT NULL,
    to_price_minor BIGINT(20) NOT NULL,
    raised TINYINT(4) NOT NULL DEFAULT 0 COMMENT '是否涨价',
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_request_at (request_no,at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='报价改价留痕（C 端公示用）';

CREATE TABLE IF NOT EXISTS mkt_request
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    request_no VARCHAR(64) NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT DEFAULT NULL,
    images TEXT DEFAULT NULL COMMENT 'JSON 数组',
    expect_count INT(11) NOT NULL DEFAULT 1,
    interest_count INT(11) NOT NULL DEFAULT 0 COMMENT '+1 数：**意向，不是订单**',
    status VARCHAR(16) NOT NULL DEFAULT 'COLLECTING' COMMENT 'COLLECTING/QUOTED/LOCKED/CONFIRMED/CLOSED',
    chosen_quote_no VARCHAR(64) DEFAULT NULL,
    locked_price BIGINT(20) DEFAULT NULL COMMENT '锁定的单价快照',
    end_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    pickup_no VARCHAR(64) DEFAULT NULL COMMENT '需求所属自提点/小区 —— 邻里的意义就在于此',
    budget_minor BIGINT(20) DEFAULT NULL COMMENT '发起人心理价位（分），可不填；填了商家报价更有的放矢',
    group_no VARCHAR(64) DEFAULT NULL COMMENT 'MATCHED 后回填：选定报价转成的正式团',
    PRIMARY KEY (id),
    UNIQUE KEY uk_request_no (request_no),
    KEY idx_status (status,end_at),
    KEY idx_owner (owner_id),
    KEY idx_request_pickup_status (pickup_no,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='邻里求团需求单';

CREATE TABLE IF NOT EXISTS mkt_request_interest
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    request_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_request_user (request_no,user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='求团 +1（意向）';

CREATE TABLE IF NOT EXISTS mkt_user_coupon
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_coupon_no VARCHAR(64) NOT NULL,
    coupon_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED/USED/EXPIRED',
    order_no VARCHAR(64) DEFAULT NULL COMMENT '用在哪一单（主单）',
    received_at BIGINT(20) NOT NULL,
    used_at BIGINT(20) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_coupon_no (user_coupon_no),
    KEY idx_user_status (user_no,status),
    KEY idx_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='用户券';

CREATE TABLE IF NOT EXISTS msg_message
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    message_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    msg_type VARCHAR(16) NOT NULL,
    title VARCHAR(128) NOT NULL,
    body VARCHAR(512) DEFAULT NULL,
    link VARCHAR(255) DEFAULT NULL COMMENT '完整页面路径带参',
    is_read TINYINT(4) NOT NULL DEFAULT 0,
    dedup_key VARCHAR(128) DEFAULT NULL,
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_no (message_no),
    UNIQUE KEY uk_msg_dedup (dedup_key),
    KEY idx_msg_user_read (user_no,is_read,at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='站内消息';

CREATE TABLE IF NOT EXISTS msg_subscribe
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_no VARCHAR(64) NOT NULL,
    template_id VARCHAR(64) NOT NULL,
    accepted TINYINT(4) NOT NULL DEFAULT 0,
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sub_user_template (user_no,template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='订阅消息授权';

CREATE TABLE IF NOT EXISTS msg_ticket
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    ticket_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    subject VARCHAR(128) NOT NULL,
    content VARCHAR(1024) NOT NULL,
    order_no VARCHAR(64) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    reply VARCHAR(1024) DEFAULT NULL,
    replied_at BIGINT(20) DEFAULT NULL,
    replied_by VARCHAR(64) DEFAULT NULL COMMENT '客服 staffNo —— 代客操作要能追到人',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_no (ticket_no),
    KEY idx_ticket_user (user_no,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='客服工单';

CREATE TABLE IF NOT EXISTS ord_after_sale
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    after_sale_no VARCHAR(64) NOT NULL,
    sub_order_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL COMMENT 'REFUND_ONLY/RETURN_REFUND/EXCHANGE',
    status VARCHAR(16) NOT NULL DEFAULT 'APPLIED',
    reason VARCHAR(255) NOT NULL,
    images TEXT DEFAULT NULL COMMENT 'JSON 数组：凭证图',
    refund_minor BIGINT(20) NOT NULL DEFAULT 0,
    instant TINYINT(4) NOT NULL DEFAULT 0,
    merchant_remark VARCHAR(255) DEFAULT NULL COMMENT '驳回理由：用户要据此决定是否申诉',
    express_company VARCHAR(64) DEFAULT NULL,
    express_no VARCHAR(64) DEFAULT NULL,
    liability VARCHAR(16) DEFAULT NULL COMMENT 'PLATFORM/MERCHANT/PICKUP',
    split_reversed TINYINT(4) NOT NULL DEFAULT 0,
    refunded_at BIGINT(20) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    dispute_reason VARCHAR(512) DEFAULT NULL COMMENT '上升平台时用户填的申诉理由：裁决要听双方',
    points_offset_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '退款时积分扣不回来的部分，折成现金从退款里扣（分）。**退款单必须明示**，不写清楚必然客诉',
    refund_payment_no VARCHAR(64) DEFAULT NULL COMMENT '对应的退款流水号（stl_payment.payment_no）。退款要重试，重试要幂等，幂等靠它',
    PRIMARY KEY (id),
    UNIQUE KEY uk_after_sale_no (after_sale_no),
    KEY idx_sub_order (sub_order_no),
    KEY idx_user (user_no,status),
    KEY idx_entity (entity_no,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='售后单（子单粒度）';

CREATE TABLE IF NOT EXISTS ord_item
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    sub_order_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    goods_no VARCHAR(64) NOT NULL,
    sku_no VARCHAR(64) NOT NULL,
    title VARCHAR(255) DEFAULT NULL COMMENT '下单时快照，不随商品改名变动',
    cover VARCHAR(512) DEFAULT NULL,
    spec VARCHAR(128) DEFAULT NULL,
    price BIGINT(20) NOT NULL DEFAULT 0,
    qty INT(11) NOT NULL DEFAULT 1,
    amount BIGINT(20) NOT NULL DEFAULT 0,
    category_type VARCHAR(16) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    nominal_gram INT(11) DEFAULT NULL COMMENT '标称克重：FRESH 按重计价时下单锁定的重量',
    weighed TINYINT(4) NOT NULL DEFAULT 0 COMMENT '是否已实际称重；未称重时差价为 0',
    is_gift TINYINT(4) NOT NULL DEFAULT 0 COMMENT '赠品行：价格为 0，不参与计价，履约时随单发出',
    weigh_adjust_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '本行称重差价（分）：正 = 补款，负 = 退款。未称重时为 0',
    PRIMARY KEY (id),
    KEY idx_sub_order (sub_order_no),
    KEY idx_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='订单行（商品快照）';

CREATE TABLE IF NOT EXISTS ord_order
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    community_no VARCHAR(64) DEFAULT NULL,
    pay_amount BIGINT(20) NOT NULL DEFAULT 0,
    goods_amount BIGINT(20) NOT NULL DEFAULT 0,
    freight_amount BIGINT(20) NOT NULL DEFAULT 0,
    discount_amount BIGINT(20) NOT NULL DEFAULT 0,
    currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
    status VARCHAR(16) NOT NULL DEFAULT 'WAIT_PAY',
    pay_channel VARCHAR(16) DEFAULT NULL,
    pay_trade_no VARCHAR(64) DEFAULT NULL COMMENT '成功那笔的通道交易号，**快照** —— 真源是 stl_payment（一单可能有多次支付尝试）',
    paid_at BIGINT(20) DEFAULT NULL,
    pay_deadline_at BIGINT(20) DEFAULT NULL,
    cancel_reason VARCHAR(255) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    pay_scene VARCHAR(16) DEFAULT NULL COMMENT '下单端 MP_WECHAT/MP_ALIPAY/IOS/ANDROID/H5',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user_status (user_no,status),
    KEY idx_status_expire (status,pay_deadline_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='主订单：用户视角，一次支付';

CREATE TABLE IF NOT EXISTS ord_status_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    sub_order_no VARCHAR(64) NOT NULL COMMENT '时间线是子单粒度（Q6）',
    status VARCHAR(16) NOT NULL,
    label VARCHAR(64) DEFAULT NULL COMMENT '展示文案',
    operator_type VARCHAR(16) NOT NULL DEFAULT 'SYSTEM' COMMENT 'USER/MERCHANT/PLATFORM/SYSTEM',
    operator_no VARCHAR(64) DEFAULT NULL COMMENT '客服代客操作必须留痕',
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_sub_order (sub_order_no,at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='订单状态时间线（append-only）';

CREATE TABLE IF NOT EXISTS ord_sub_order
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    sub_order_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    entity_name VARCHAR(128) DEFAULT NULL,
    fulfillment VARCHAR(24) DEFAULT NULL,
    pickup_no VARCHAR(64) DEFAULT NULL,
    address_id VARCHAR(64) DEFAULT NULL,
    traffic_source VARCHAR(24) DEFAULT NULL COMMENT 'MERCHANT_OWNED/PLATFORM，下单时固化',
    goods_amount BIGINT(20) NOT NULL DEFAULT 0,
    freight_amount BIGINT(20) NOT NULL DEFAULT 0,
    discount_amount BIGINT(20) NOT NULL DEFAULT 0,
    pay_amount BIGINT(20) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'WAIT_PAY',
    verify_code VARCHAR(16) DEFAULT NULL COMMENT '取货码，支付成功后生成',
    remark VARCHAR(255) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    pickup_name VARCHAR(128) DEFAULT NULL,
    discount_platform BIGINT(20) NOT NULL DEFAULT 0,
    discount_merchant BIGINT(20) NOT NULL DEFAULT 0,
    weigh_adjust_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '称重差价合计（分）= Σ 本子单各行的 weigh_adjust_minor。**汇总值** —— 明细在 ord_item 上',
    appointment_at BIGINT(20) DEFAULT NULL COMMENT 'APPOINTMENT 履约：预约开始时间戳',
    group_no VARCHAR(64) DEFAULT NULL COMMENT '参团下单时的团号；邻里自提的核销作用域靠它裁剪',
    express_no VARCHAR(64) DEFAULT NULL COMMENT 'EXPRESS 履约：快递单号，发货后才有',
    buyer_nickname VARCHAR(64) DEFAULT NULL COMMENT '下单人昵称快照：团长视角（分拣单/核销台）要看得见是谁的单',
    reviewed TINYINT(4) NOT NULL DEFAULT 0 COMMENT '是否已评价：一单一评的判据',
    points_deduct INT(11) NOT NULL DEFAULT 0 COMMENT '本单抵扣的积分数。**平台内部字段，不下发商家端**',
    points_deduct_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '本单积分抵扣金额（分）。**平台内部字段，不下发商家端** —— 商家按订单全额收款。上限 = 券后金额 30%，运费不参与',
    points_granted TINYINT(4) NOT NULL DEFAULT 0 COMMENT '发放幂等标记：防重复核销重复发分',
    points_fee_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '本单发放积分对应的费用金（分）。发放时算定，结算时扣',
    merchant_recv_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '商家实收（分）= **订单金额**（含被积分抵掉的部分）− 通道费 − 佣金 − 履约服务费 − 积分服务费。**不减积分抵扣** —— 商家不感知它',
    channel_fee_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '通道手续费（分）：按**用户实付**算（平台补贴那部分不走支付通道）。支付时即确定',
    commission_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '平台佣金（分）：费率按 traffic_source 分档，下单时快照',
    service_fee_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '履约服务费（分）：自提单给承接方，非自提单为 0',
    store_no VARCHAR(64) DEFAULT NULL COMMENT '履约门店。结算仍按 entity_no —— 两个键答两个问题（M2 双写）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sub_order_no (sub_order_no),
    UNIQUE KEY uk_verify_code (verify_code),
    KEY idx_order (order_no),
    KEY idx_entity_status (entity_no,status),
    KEY idx_pickup_code (pickup_no,verify_code),
    KEY idx_sub_user_status (user_no,status),
    KEY idx_sub_pickup_status (pickup_no,status),
    KEY idx_sub_order_group (group_no),
    KEY idx_sub_order_entity_recv (entity_no,status,created_at),
    KEY idx_sub_order_store (store_no,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='子订单：商家视角，一次分账一条履约链';

CREATE TABLE IF NOT EXISTS prd_category
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    category_no VARCHAR(64) NOT NULL,
    parent_no VARCHAR(64) DEFAULT NULL COMMENT '一级类目为空',
    level INT(11) NOT NULL DEFAULT 1,
    name VARCHAR(64) NOT NULL,
    icon VARCHAR(512) DEFAULT NULL,
    sort INT(11) NOT NULL DEFAULT 0,
    attr_template TEXT DEFAULT NULL COMMENT 'JSON：五品类属性模板（P-3.1.2）',
    qualification_required VARCHAR(512) DEFAULT NULL COMMENT 'JSON：经营该类目需要的资质（P-3.1.4）',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_no (category_no),
    KEY idx_parent_sort (parent_no,sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='三级类目树';

CREATE TABLE IF NOT EXISTS prd_community_pool
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    community_no VARCHAR(64) NOT NULL,
    goods_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    sort_weight INT(11) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_community_goods (community_no,goods_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='社区商品池：只决定可见性，不存价';

CREATE TABLE IF NOT EXISTS prd_goods
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    goods_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    subtitle VARCHAR(255) DEFAULT NULL,
    cover VARCHAR(512) DEFAULT NULL,
    images TEXT DEFAULT NULL COMMENT 'JSON 数组',
    type VARCHAR(16) NOT NULL COMMENT 'NORMAL/FRESH/SERVICE/VIRTUAL/CARD',
    category_no VARCHAR(64) DEFAULT NULL,
    fulfillments VARCHAR(255) DEFAULT NULL COMMENT 'JSON 数组',
    spec_groups TEXT DEFAULT NULL COMMENT 'JSON',
    rating INT(11) NOT NULL DEFAULT 50,
    rating_count INT(11) NOT NULL DEFAULT 0,
    sales INT(11) NOT NULL DEFAULT 0,
    limit_per_user INT(11) NOT NULL DEFAULT 0,
    on_sale TINYINT(4) NOT NULL DEFAULT 0,
    audit_status VARCHAR(16) NOT NULL DEFAULT 'AUDITING',
    cutoff_at BIGINT(20) DEFAULT NULL,
    arrival_desc VARCHAR(128) DEFAULT NULL,
    weighed TINYINT(4) DEFAULT NULL,
    origin VARCHAR(64) DEFAULT NULL,
    duration_min INT(11) DEFAULT NULL,
    store_name VARCHAR(128) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    points_config INT(11) DEFAULT NULL COMMENT '本商品发放积分数；NULL 走成交额兜底比例。赠品行不发',
    group_price_minor BIGINT(20) DEFAULT NULL COMMENT '团购价（分）。为 NULL 即「未开放拼团」，C 端开团直接拒',
    group_min_count INT(11) DEFAULT NULL COMMENT '起团人数；未配时按 2 人起',
    sellable_override JSON DEFAULT NULL COMMENT '端级可售例外 {"IOS":false}；空则按 sys_channel_category_rule',
    title_i18n TEXT DEFAULT NULL COMMENT 'JSON {"en":"...","ar":"..."}；缺的语言回落 title（中文权威）',
    subtitle_i18n TEXT DEFAULT NULL COMMENT 'JSON，同 title_i18n',
    PRIMARY KEY (id),
    UNIQUE KEY uk_goods_no (goods_no),
    KEY idx_entity (entity_no),
    KEY idx_type (type),
    KEY idx_title (title(32))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='商品 SPU（价格不在这张表）';

CREATE TABLE IF NOT EXISTS prd_sku
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    sku_no VARCHAR(64) NOT NULL,
    goods_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    market VARCHAR(8) NOT NULL DEFAULT 'CN',
    option_values VARCHAR(512) DEFAULT NULL COMMENT 'JSON 数组',
    spec VARCHAR(128) DEFAULT NULL COMMENT '展示文案，后端下发',
    price BIGINT(20) NOT NULL COMMENT '最小货币单位（分）',
    origin_price BIGINT(20) DEFAULT NULL,
    stock INT(11) NOT NULL DEFAULT 0,
    locked_stock INT(11) NOT NULL DEFAULT 0,
    nominal_gram INT(11) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_entity_sku_market (entity_no,sku_no,market),
    KEY idx_goods (goods_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='SKU 与价格';

CREATE TABLE IF NOT EXISTS prd_spec_template
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    template_no VARCHAR(64) NOT NULL,
    scope VARCHAR(16) NOT NULL DEFAULT 'MERCHANT' COMMENT 'PLATFORM(平台统一维护)/MERCHANT(商家自存)',
    category_type VARCHAR(16) DEFAULT NULL COMMENT '平台模板按类目推荐；商家模板不限类目',
    name VARCHAR(64) NOT NULL COMMENT '规格维度名，如「重量」「香型」',
    options JSON NOT NULL COMMENT '[{code,label}]；来自平台模板的有 code，手输的没有',
    entity_no VARCHAR(64) DEFAULT NULL COMMENT 'scope=MERCHANT 时归属的商家',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_template_no (template_no),
    KEY idx_scope_category (scope,category_type),
    KEY idx_entity (entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='规格模板（平台维护 + 商家自存）';

CREATE TABLE IF NOT EXISTS prd_stock_lock
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    lock_no VARCHAR(64) NOT NULL COMMENT '= 订单号',
    sku_no VARCHAR(64) NOT NULL,
    qty INT(11) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'LOCKED' COMMENT 'LOCKED/RELEASED/CONFIRMED',
    locked_at DATETIME NOT NULL,
    settled_at DATETIME DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_lock_status (lock_no,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='库存锁定明细：释放与确认据此幂等';

CREATE TABLE IF NOT EXISTS pts_user_account
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_no VARCHAR(64) NOT NULL,
    balance BIGINT(20) NOT NULL DEFAULT 0 COMMENT '可用余额（派生，以流水为准）',
    total_earn BIGINT(20) NOT NULL DEFAULT 0,
    total_use BIGINT(20) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    market VARCHAR(8) NOT NULL DEFAULT 'CN' COMMENT '市场隔离键',
    pending_balance BIGINT(20) NOT NULL DEFAULT 0 COMMENT '待生效积分：已发放但未过售后期。**不计入 balance** —— balance 只放能花的',
    expire_at BIGINT(20) DEFAULT NULL COMMENT '账户到期时刻：**任何积分变动都会把它推后**（滚动续期）。到期则该市场下积分全部清零',
    last_active_at BIGINT(20) DEFAULT NULL COMMENT '最近一次积分变动时刻。expire_at = last_active_at + 无活动期，单独存一列是为了让「为什么是这个到期日」一眼可查',
    expire_notified_at BIGINT(20) DEFAULT NULL COMMENT '到期提醒发出时刻。**幂等用**：任务重跑不能把同一个用户提醒两遍',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pts_account_user_market (user_no,market),
    KEY idx_pts_account_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='用户积分账户（锁行 + 派生余额）';

CREATE TABLE IF NOT EXISTS pts_user_ledger
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    ledger_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    biz_type VARCHAR(16) NOT NULL COMMENT 'EARN 发放/USE 使用/REFUND 退回/EXPIRE 到期清零（整账户一次）/REVOKE 退款扣回',
    points BIGINT(20) NOT NULL COMMENT '带符号：EARN/REFUND 为正，USE/EXPIRE 为负',
    balance_after BIGINT(20) NOT NULL COMMENT '快照，用于定位「从哪条开始错的」',
    issuer_merchant_no VARCHAR(64) DEFAULT NULL COMMENT '仅 EARN：谁发的。**只用于追溯与统计**，不参与任何资金流动',
    sub_order_no VARCHAR(64) DEFAULT NULL,
    remark VARCHAR(255) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    available_at BIGINT(20) DEFAULT NULL COMMENT '仅 EARN：可用时间（售后期结束）。此前分**可见不可用**，计入 pending_balance 而非 balance',
    market VARCHAR(8) NOT NULL DEFAULT 'CN',
    acceptor_merchant_no VARCHAR(64) DEFAULT NULL COMMENT '仅 USE：收单方，池子付钱给它。**发放方不记** —— 发分时已付费，与本次使用无关',
    amount_minor BIGINT(20) DEFAULT NULL COMMENT '仅 USE：本次抵扣的金额（分）。与 ord_sub_order.points_deduct_minor 勾稽',
    rate_snapshot INT(11) DEFAULT NULL COMMENT '汇率快照（多少分 = 1 元）。调汇率不改变已发生的账',
    status VARCHAR(16) DEFAULT NULL COMMENT '仅 USE：PENDING 预占（池子未付款）/CONFIRMED 兑付成立/REVERSED 撤销',
    period VARCHAR(8) DEFAULT NULL COMMENT '仅 USE：账期 YYYYMM，CONFIRMED 时落定',
    confirmed_at BIGINT(20) DEFAULT NULL,
    currency VARCHAR(8) DEFAULT NULL COMMENT 'amount_minor 的币种，随 market 定',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pts_ledger_no (ledger_no),
    KEY idx_pts_ledger_user (user_no,id),
    KEY idx_pts_ledger_sub_order (sub_order_no,biz_type),
    KEY idx_pts_activate (available_at,biz_type),
    KEY idx_pts_ledger_acceptor (period,status,acceptor_merchant_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='用户积分流水（真源）。EARN/USE/REFUND/EXPIRE/REVOKE 五种行，**没有批次概念**（V30 起按账户滚动到期）';

CREATE TABLE IF NOT EXISTS rvw_appeal
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    appeal_no VARCHAR(64) NOT NULL,
    review_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    reason VARCHAR(512) NOT NULL COMMENT '申诉理由，商家填',
    images JSON DEFAULT NULL COMMENT '举证图：聊天记录、物流截图',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/UPHELD(差评下架)/REJECTED(评价保留)',
    submitted_at BIGINT(20) NOT NULL,
    verdict VARCHAR(512) DEFAULT NULL COMMENT '裁决说明：成立与驳回都必须写',
    decided_at BIGINT(20) DEFAULT NULL,
    decided_by VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_appeal_no (appeal_no),
    UNIQUE KEY uk_review (review_no),
    KEY idx_status (status,submitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='商家对差评的申诉（P-13.1.3 裁决入口）';

CREATE TABLE IF NOT EXISTS rvw_review
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    review_no VARCHAR(64) NOT NULL,
    sub_order_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    goods_no VARCHAR(64) NOT NULL,
    sku_no VARCHAR(64) DEFAULT NULL,
    entity_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    nickname VARCHAR(64) DEFAULT NULL,
    avatar VARCHAR(512) DEFAULT NULL,
    rating TINYINT(4) NOT NULL COMMENT '总分 1-5',
    score_goods TINYINT(4) DEFAULT NULL COMMENT '商品本身 1-5',
    score_fulfillment TINYINT(4) DEFAULT NULL COMMENT '履约（快慢/包装/缺损）1-5',
    score_service TINYINT(4) DEFAULT NULL COMMENT '服务（沟通/售后态度）1-5',
    content VARCHAR(1024) DEFAULT NULL,
    images JSON DEFAULT NULL,
    spec VARCHAR(255) DEFAULT NULL COMMENT '购买规格快照：让人知道这条评价说的是哪个 SKU',
    like_count INT(11) NOT NULL DEFAULT 0,
    reply VARCHAR(512) DEFAULT NULL COMMENT '商家回复；一条评价只能回一次',
    replied_at BIGINT(20) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PASSED' COMMENT 'PENDING/PASSED/REJECTED',
    reject_reason VARCHAR(255) DEFAULT NULL COMMENT '驳回原因：与门店审核同一条规矩 —— 驳回必须写清楚',
    risk_flags JSON DEFAULT NULL COMMENT 'SAME_DEVICE/SAME_IP/TEXT_DUP/BURST：给人审的线索',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_no (review_no),
    UNIQUE KEY uk_order_goods (sub_order_no,goods_no),
    KEY idx_goods_status (goods_no,status),
    KEY idx_entity_status (entity_no,status),
    KEY idx_user (user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='商品评价（含三维分）';

CREATE TABLE IF NOT EXISTS rvw_review_like
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    review_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_user (review_no,user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='评价点赞明细（likeCount 的真源）';

CREATE TABLE IF NOT EXISTS stl_bill
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    settle_no VARCHAR(64) NOT NULL,
    sub_order_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    gross_minor BIGINT(20) NOT NULL COMMENT '结算基数（分）= 订单金额。补差后二级商户账户里就是这个数（用户实付 + 平台补差），不是记账约定',
    commission_minor BIGINT(20) NOT NULL DEFAULT 0,
    service_fee_minor BIGINT(20) NOT NULL DEFAULT 0,
    net_minor BIGINT(20) NOT NULL DEFAULT 0,
    traffic_source VARCHAR(24) DEFAULT NULL,
    commission_rate INT(11) NOT NULL DEFAULT 0 COMMENT '万分比，落库快照 —— 费率会变，历史账不能跟着变',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    split_at BIGINT(20) DEFAULT NULL,
    retry_count INT(11) NOT NULL DEFAULT 0,
    last_error VARCHAR(512) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    pay_channel VARCHAR(16) NOT NULL DEFAULT 'WECHAT' COMMENT '分账实现按它路由，对账按它切分',
    pay_scene VARCHAR(16) DEFAULT NULL,
    channel_fee_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '该笔实际扣的通道手续费（分）',
    channel_fee_rate INT(11) NOT NULL DEFAULT 0 COMMENT '通道费率快照（万分比）',
    channel_fee_source VARCHAR(16) DEFAULT NULL COMMENT 'STANDARD/PROMO',
    fee_bearer VARCHAR(16) NOT NULL DEFAULT 'MERCHANT',
    points_fee_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '本单的积分费用金（分）：商家发分即扣，结算时从货款扣走进积分池',
    accrued_at BIGINT(20) DEFAULT NULL COMMENT '计提时间（支付成功时）。与 split_at（实际分账时间）分开 —— 账面与资金是两个时点',
    split_amount_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '实际向通道发起分账的金额（分）= 佣金 + 履约服务费 + 积分服务费。**分账指令以它为准**，不要在发起时重算 —— 算式会变，历史账不能跟着变',
    PRIMARY KEY (id),
    UNIQUE KEY uk_settle_no (settle_no),
    UNIQUE KEY uk_sub_order (sub_order_no),
    KEY idx_entity_status (entity_no,status),
    KEY idx_status_created (status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='结算单（按子单）';

CREATE TABLE IF NOT EXISTS stl_payment
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    payment_no VARCHAR(64) NOT NULL COMMENT '本方流水号。**幂等键** —— 重试用同一个号，通道按它去重',
    direction VARCHAR(16) NOT NULL COMMENT 'PAY 收款/REFUND 退款/SUBSIDY 补差（积分抵扣，分账前补进二级商户账户）/SUBSIDY_REVERSE 补差回退/PAYOUT 打款给商家',
    order_no VARCHAR(64) NOT NULL,
    sub_order_no VARCHAR(64) DEFAULT NULL COMMENT 'REFUND 退的是哪个子单；SUBSIDY 补的是哪个子单。收款挂主订单，此列为空',
    after_sale_no VARCHAR(64) DEFAULT NULL COMMENT '仅 REFUND：对应售后单。部分退款会有多条，各自一次通道调用',
    user_no VARCHAR(64) NOT NULL,
    pay_channel VARCHAR(16) NOT NULL COMMENT 'WECHAT/ALIPAY',
    pay_scene VARCHAR(16) DEFAULT NULL COMMENT '下单端 MP_WECHAT/MP_ALIPAY/IOS/ANDROID/H5。**退款也要记** —— 退回原路，端不同接口不同',
    pay_method VARCHAR(16) DEFAULT NULL COMMENT 'JSAPI/APP/H5/NATIVE',
    amount_minor BIGINT(20) NOT NULL COMMENT '金额（分），恒为正。方向看 direction，不用负数 —— 负数金额在对账单上没有对应概念',
    currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
    status VARCHAR(16) NOT NULL DEFAULT 'INIT' COMMENT 'INIT 已创建未调用/PENDING 已调用待回调/SUCCESS/FAILED/CLOSED 超时关闭',
    out_trade_no VARCHAR(64) DEFAULT NULL COMMENT '我方给通道的商户订单号。**用户报障时报的是这个**',
    trade_no VARCHAR(64) DEFAULT NULL COMMENT '通道交易号（微信 transaction_id / 支付宝 trade_no）。**对账单上是这个**',
    channel_fee_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '通道实扣手续费（分）。以回执为准，不是按费率算出来的',
    succeeded_at BIGINT(20) DEFAULT NULL,
    closed_at BIGINT(20) DEFAULT NULL,
    err_code VARCHAR(64) DEFAULT NULL,
    err_msg VARCHAR(255) DEFAULT NULL,
    raw_notify TEXT DEFAULT NULL COMMENT '通道回调原文。**唯一的资金凭据** —— 出纠纷要原文，解析后的字段不能举证',
    reconciled_at BIGINT(20) DEFAULT NULL COMMENT '与通道账单核对通过的时间。**为空 = 未对账**，掉单只能靠这个发现',
    reconcile_batch VARCHAR(32) DEFAULT NULL COMMENT '对账批次（通道账单日期 YYYYMMDD）',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    entity_no VARCHAR(64) DEFAULT NULL COMMENT '仅 PAYOUT：打给哪个商家。收款与退款的对手方是用户，不填',
    PRIMARY KEY (id),
    UNIQUE KEY uk_stl_payment_no (payment_no),
    UNIQUE KEY uk_stl_payment_trade (pay_channel,trade_no),
    KEY idx_stl_payment_order (order_no,direction),
    KEY idx_stl_payment_after_sale (after_sale_no),
    KEY idx_stl_payment_recon (reconcile_batch,reconciled_at),
    KEY idx_stl_payment_status (status,succeeded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='资金流水（append 为主，带通道回执与对账状态）：收款 / 退款 / 补差 / 补差回退 / 打款';

CREATE TABLE IF NOT EXISTS stl_points_pool
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    flow_no VARCHAR(64) NOT NULL,
    direction VARCHAR(8) NOT NULL COMMENT 'IN/OUT',
    pool_type VARCHAR(24) NOT NULL COMMENT 'MERCHANT_PAY 补贴收单商家/MERCHANT_RECEIVE 收商家的发分服务费/PLATFORM_ISSUE 平台自发成本/EXPIRE_INCOME 到期转平台收入/RECOVERY 追回/PENALTY 罚没/BAD_DEBT 坏账',
    amount_minor BIGINT(20) NOT NULL,
    balance_after BIGINT(20) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL,
    period VARCHAR(8) DEFAULT NULL,
    ref_no VARCHAR(64) DEFAULT NULL COMMENT '关联单据：MERCHANT_RECEIVE 指 stl_bill.settle_no（收的发分服务费）；MERCHANT_PAY 指补贴批次',
    remark VARCHAR(255) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    market VARCHAR(8) NOT NULL DEFAULT 'CN',
    currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
    pay_channel VARCHAR(16) NOT NULL DEFAULT 'WECHAT' COMMENT '这笔钱进/出哪个通道的账户。balance_after 按 (market, pay_channel) 各自累计，不是全局一个数',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pts_pool_flow_no (flow_no),
    KEY idx_pts_pool_period (period,pool_type),
    KEY idx_pts_pool_entity (entity_no,period),
    KEY idx_stl_pool_channel (market,pay_channel,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='平台积分营销资金账户流水。**平台自己的钱**，用于兑现平台发出的积分（同平台优惠券补差）';

CREATE TABLE IF NOT EXISTS stl_split_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    settle_no VARCHAR(64) NOT NULL,
    sub_order_no VARCHAR(64) NOT NULL,
    split_action VARCHAR(16) NOT NULL,
    amount_minor BIGINT(20) NOT NULL,
    request_no VARCHAR(64) NOT NULL,
    result VARCHAR(16) NOT NULL COMMENT 'SUCCESS/FAILED',
    provider_no VARCHAR(64) DEFAULT NULL COMMENT '支付服务商回执号',
    message VARCHAR(512) DEFAULT NULL,
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_split_request_no (request_no),
    KEY idx_settle (settle_no,at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='分账指令与回执（append-only）';

CREATE TABLE IF NOT EXISTS sys_audit_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    staff_no VARCHAR(64) NOT NULL,
    staff_name VARCHAR(64) DEFAULT NULL,
    op_action VARCHAR(64) NOT NULL COMMENT '操作码，如 MERCHANT_AUDIT',
    target VARCHAR(128) DEFAULT NULL COMMENT '被操作对象的业务键',
    detail VARCHAR(512) DEFAULT NULL,
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_staff_at (staff_no,at),
    KEY idx_audit_target (target)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='操作审计（append-only）';

CREATE TABLE IF NOT EXISTS sys_channel_category_rule
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    scene VARCHAR(16) NOT NULL COMMENT 'MP_WECHAT/MP_ALIPAY/IOS/ANDROID/H5',
    category_type VARCHAR(16) NOT NULL COMMENT 'GOODS/FRESH/SERVICE/VIRTUAL/CARD',
    sellable TINYINT(4) NOT NULL DEFAULT 1,
    reason VARCHAR(255) DEFAULT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ccr_scene_category (scene,category_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='端 × 品类 可售规则（iOS IAP 约束等）';

CREATE TABLE IF NOT EXISTS sys_idempotent
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    idem_key VARCHAR(128) NOT NULL COMMENT '客户端 Idempotency-Key',
    endpoint VARCHAR(128) NOT NULL COMMENT '端点，如 POST /mp/order',
    user_no VARCHAR(64) DEFAULT NULL,
    result_json TEXT DEFAULT NULL COMMENT '首次成功结果快照，重放时原样返回',
    created_at DATETIME NOT NULL,
    expire_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_key_endpoint (idem_key,endpoint),
    KEY idx_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='幂等记录：下单/支付/退款/核销必接';

CREATE TABLE IF NOT EXISTS sys_industry
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    industry VARCHAR(24) NOT NULL COMMENT '行业码。取值域见 shared 的 Industry 联合类型',
    name VARCHAR(32) NOT NULL COMMENT '展示名，商家入驻时看到的就是它',
    sort INT(11) NOT NULL DEFAULT 0,
    enabled TINYINT(4) NOT NULL DEFAULT 1,
    wechat_micro_allowed TINYINT(4) NOT NULL DEFAULT 0 COMMENT '该行业能否以**小微**主体在微信进件。默认 0：**默认允许 = 默认让商家撞墙**',
    alipay_micro_allowed TINYINT(4) NOT NULL DEFAULT 0 COMMENT '支付宝同上。⚠️ 支付宝的行业限制**尚未确认**，故全部保守置 0 —— 确认前不放开小微',
    points_forced TINYINT(4) NOT NULL DEFAULT 0 COMMENT '该行业是否**强制开启积分**（商家不可自行关闭）。mch_entity.points_forced 的来源',
    remark VARCHAR(255) DEFAULT NULL COMMENT '给运营看的说明：为什么这个行业是这个准入结论',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_industry (industry),
    KEY idx_sys_industry_sort (enabled,sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='行业注册表：商家的基础属性，平台维护。通道准入（能否小微）按它判';

CREATE TABLE IF NOT EXISTS sys_legal_form
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    legal_form VARCHAR(24) NOT NULL COMMENT '权威码，取值域见 shared 的 SubjectType 联合类型',
    name VARCHAR(32) NOT NULL COMMENT '展示名，入驻表单上看到的就是它',
    sort INT(11) NOT NULL DEFAULT 0,
    enabled TINYINT(4) NOT NULL DEFAULT 1,
    legacy_subject VARCHAR(24) DEFAULT NULL COMMENT '对应 shared MerchantSubject 的旧取值（PERSONAL/INDIVIDUAL_BIZ/COMPANY）',
    wechat_code VARCHAR(32) DEFAULT NULL COMMENT '微信进件的主体类型码；为空表示微信不收这种主体',
    alipay_code VARCHAR(32) DEFAULT NULL COMMENT '支付宝同上',
    need_license TINYINT(4) NOT NULL DEFAULT 1 COMMENT '是否需要营业执照。小微不需要，这正是它存在的意义',
    settle_account_type VARCHAR(24) DEFAULT NULL COMMENT '结算账户形态：PERSONAL_OPENID（打到个人）/ MERCHANT_ID（打到对公）',
    industry_gated TINYINT(4) NOT NULL DEFAULT 0 COMMENT '选这种主体时是否要过 sys_industry 的行业白名单。仅小微为 1',
    remark VARCHAR(255) DEFAULT NULL COMMENT '给运营看的说明',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_legal_form (legal_form),
    KEY idx_sys_legal_form_sort (enabled,sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='法律形态注册表（小微/个体户/企业）：表管通道映射与资质要求，类型管取值';

CREATE TABLE IF NOT EXISTS sys_outbox
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    event_no VARCHAR(64) NOT NULL COMMENT '事件业务键',
    aggregate_type VARCHAR(32) NOT NULL COMMENT '聚合类型 ORDER/SUB_ORDER/...',
    aggregate_id VARCHAR(64) NOT NULL COMMENT '聚合业务键',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型 ORDER_PAID/...',
    payload TEXT NOT NULL COMMENT 'JSON，自带消费方所需全部字段',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/FAILED',
    retry_count INT(11) NOT NULL DEFAULT 0,
    next_retry_at DATETIME DEFAULT NULL,
    last_error VARCHAR(512) DEFAULT NULL,
    created_at DATETIME NOT NULL,
    sent_at DATETIME DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_no (event_no),
    KEY idx_status_retry (status,next_retry_at),
    KEY idx_aggregate (aggregate_type,aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='事务性发件箱：业务与事件同事务落库';

CREATE TABLE IF NOT EXISTS sys_pay_channel
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    pay_channel VARCHAR(16) NOT NULL COMMENT 'WECHAT/ALIPAY。取值域见 shared 的 PayChannel 联合类型',
    name VARCHAR(32) NOT NULL COMMENT '展示名',
    enabled TINYINT(4) NOT NULL DEFAULT 1,
    supports_subsidy TINYINT(4) NOT NULL DEFAULT 0 COMMENT '能否**补差**（分账前把平台补贴转入二级商户账户）。为 0 时该通道**不开积分抵扣** —— 不做兜底记账',
    supports_split TINYINT(4) NOT NULL DEFAULT 1 COMMENT '能否分账',
    supports_payout TINYINT(4) NOT NULL DEFAULT 0 COMMENT '能否直接打款给商家',
    pay_methods JSON DEFAULT NULL COMMENT '["JSAPI","APP","H5","NATIVE"]',
    markets JSON DEFAULT NULL COMMENT '该通道在哪些市场可用，如 ["CN"]',
    pool_account_ref VARCHAR(64) DEFAULT NULL COMMENT '平台在该通道的资金账户标识，**只存对账用的引用，不存密钥或完整账号**',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    max_partial_refunds INT(11) NOT NULL DEFAULT 0 COMMENT '单笔订单最多可部分退款几次（微信 50，0 = 未知/不限）。**不能设计成「每件商品独立退」的高频路径**',
    refund_interval_seconds INT(11) NOT NULL DEFAULT 0 COMMENT '两次退款调用的最小间隔（微信 60 秒）。批量退款要按它排队，否则会被通道拒绝',
    max_split_rate INT(11) NOT NULL DEFAULT 10000 COMMENT '单笔交易最高分账比例（万分比；支付宝直付通 3000 = 30%）。我们分走佣金+服务费约 3–5%，安全',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_pay_channel (pay_channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='支付通道注册表：取值域与能力位。积分抵扣是否可用由 supports_subsidy 决定';

CREATE TABLE IF NOT EXISTS sys_ops_staff
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    staff_no VARCHAR(64) NOT NULL,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(128) NOT NULL,
    real_name VARCHAR(64) DEFAULT NULL,
    roles VARCHAR(255) DEFAULT NULL COMMENT 'JSON 数组：角色码',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ops_staff_no (staff_no),
    UNIQUE KEY uk_ops_staff_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='平台运营账号（与商家账号 mch_account 是两套人，键 staff_no 从此只有一个含义）';

CREATE TABLE IF NOT EXISTS trd_cart_item
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_no VARCHAR(64) NOT NULL,
    goods_no VARCHAR(64) NOT NULL,
    sku_no VARCHAR(64) NOT NULL,
    qty INT(11) NOT NULL DEFAULT 1,
    selected TINYINT(4) NOT NULL DEFAULT 1,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_user (user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='购物车（不存价，读时实时算）';

CREATE TABLE IF NOT EXISTS usr_address
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    address_id VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL COMMENT '收件人',
    phone VARCHAR(32) NOT NULL COMMENT '一期明文存储，出参按视角脱敏（db-design §6）',
    province VARCHAR(64) DEFAULT NULL,
    city VARCHAR(64) DEFAULT NULL,
    district VARCHAR(64) DEFAULT NULL,
    detail VARCHAR(255) NOT NULL COMMENT '门牌等详细地址',
    lat_e6 INT(11) DEFAULT NULL COMMENT '配送范围校验用',
    lng_e6 INT(11) DEFAULT NULL,
    is_default TINYINT(4) NOT NULL DEFAULT 0,
    tag VARCHAR(16) DEFAULT NULL COMMENT '家/公司/其他',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_address_id (address_id),
    KEY idx_user_default (user_no,is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='地址簿';

CREATE TABLE IF NOT EXISTS mch_entity
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    logo VARCHAR(512) DEFAULT NULL,
    legal_form VARCHAR(16) DEFAULT NULL COMMENT '主体类型（通道口径）：MICRO/INDIVIDUAL/ENTERPRISE。取值域见 sys_legal_form',
    tier VARCHAR(16) DEFAULT NULL COMMENT '分层预留 P-11.1.6',
    description VARCHAR(512) DEFAULT NULL,
    owner_user_no VARCHAR(64) DEFAULT NULL COMMENT '创建者记录。**身份来源已改为 mch_account** —— 一个账号可参与多个主体',
    rating INT(11) NOT NULL DEFAULT 50 COMMENT '评分 ×10',
    rating_count INT(11) NOT NULL DEFAULT 0,
    sales_count INT(11) NOT NULL DEFAULT 0,
    goods_count INT(11) NOT NULL DEFAULT 0,
    score_goods INT(11) NOT NULL DEFAULT 50,
    score_service INT(11) NOT NULL DEFAULT 50,
    score_speed INT(11) NOT NULL DEFAULT 50,
    verified TINYINT(4) NOT NULL DEFAULT 0,
    breach_count INT(11) NOT NULL DEFAULT 0 COMMENT '毁约次数，>0 在报价卡公示（ADR-003）',
    tags VARCHAR(512) DEFAULT NULL COMMENT 'JSON 数组',
    joined_at BIGINT(20) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/SUSPENDED/FROZEN：经营状态，与入驻审核无关（审核见 mch_entity_apply）',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    store_code VARCHAR(32) DEFAULT NULL,
    service_scope VARCHAR(16) NOT NULL DEFAULT 'COMMUNITY' COMMENT 'COMMUNITY/CITY/PLATFORM：决定这家店的货在 C 端能被谁看到',
    service_city_code VARCHAR(32) DEFAULT NULL COMMENT '仅 scope=CITY 时有意义',
    points_enabled TINYINT(4) NOT NULL DEFAULT 0 COMMENT 'L3 商家级：canIssue == canAccept，同一个开关',
    points_forced TINYINT(4) NOT NULL DEFAULT 0 COMMENT '平台强制开启积分，商家不可自行关闭。默认值来自 sys_industry.points_forced（按行业），可按商家单独覆盖。需提前 30 天通知 + 费率补偿 + 申诉通道',
    industry VARCHAR(24) DEFAULT NULL COMMENT '所属行业（sys_industry.industry）。**与商品类目是两个维度** —— 行业挂商家，类目挂商品',
    PRIMARY KEY (id),
    UNIQUE KEY uk_entity_no (entity_no),
    UNIQUE KEY uk_store_code (store_code),
    KEY idx_owner (owner_user_no),
    KEY idx_mch_entity_industry (industry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='经营主体：一张营业执照的经营实体。收款商户号在 mch_payment_merchant，门店经 mch_store.entity_no 关联（可切换执照）';

CREATE TABLE IF NOT EXISTS mch_entity_apply
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    apply_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL COMMENT '申请人（C 端用户）',
    entity_no VARCHAR(64) DEFAULT NULL COMMENT '审核通过后回填',
    name VARCHAR(128) NOT NULL,
    legal_form VARCHAR(16) DEFAULT NULL COMMENT '主体类型（通道口径）：MICRO/INDIVIDUAL/ENTERPRISE。决定要不要执照与钱打到哪儿',
    contact_phone VARCHAR(32) DEFAULT NULL,
    qualifications TEXT DEFAULT NULL COMMENT 'JSON 数组：资质图',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/REVIEWING/APPROVED/REJECTED；REJECTED 可回到 PENDING 重提',
    reject_reason VARCHAR(255) DEFAULT NULL,
    audited_by VARCHAR(64) DEFAULT NULL,
    audited_at BIGINT(20) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    contact_name VARCHAR(64) DEFAULT NULL COMMENT '联系人姓名。审核要打电话找人，只有号码没有姓名不合适',
    category VARCHAR(64) DEFAULT NULL COMMENT '主营类目。决定通过后授予的类目授权范围（/ops/merchants/{no}/auth-codes 在等它）',
    description VARCHAR(512) DEFAULT NULL COMMENT '店铺简介。C 端门店主页要展示，不收就只能让商家事后补',
    service_scope VARCHAR(16) NOT NULL DEFAULT 'COMMUNITY' COMMENT 'COMMUNITY/CITY/PLATFORM：通过后写入 mch_entity.service_scope',
    community_nos VARCHAR(1024) DEFAULT NULL COMMENT 'JSON 数组。service_scope=COMMUNITY 时通过审核必填，否则该商家对谁都不可见',
    active_owner VARCHAR(64) DEFAULT NULL COMMENT '进行中(PENDING/REVIEWING)时 = user_no，进入终态时置 NULL。配合唯一键挡重复提交',
    as_pickup_point TINYINT(4) NOT NULL DEFAULT 0 COMMENT '申请人是否愿意承接自提点（ADR-005）。仅记录意愿，建点由运营在通过后另行处理',
    industry VARCHAR(24) DEFAULT NULL COMMENT '入驻时选的行业。它决定**可选的主体类型** —— 线上业态不能选小微',
    PRIMARY KEY (id),
    UNIQUE KEY uk_apply_no (apply_no),
    UNIQUE KEY uk_apply_active_owner (active_owner),
    KEY idx_apply_status (status,created_at),
    KEY idx_apply_user (user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='入驻申请：审核通过时创建 mch_entity 并回填 entity_no（幂等判据应按本表，不按人）';

CREATE TABLE IF NOT EXISTS mch_entity_community
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    community_no VARCHAR(64) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_entity_community (entity_no,community_no),
    KEY idx_community (community_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='商家覆盖的社区（scope=COMMUNITY 时生效）';

CREATE TABLE IF NOT EXISTS mch_payment_merchant
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    pay_merchant_no VARCHAR(64) DEFAULT NULL COMMENT '收款商户号业务键。mch_store.pay_merchant_no 引用它 —— 旧库一直没有这个键，门店引用的是一张没有业务键的表',
    pay_channel VARCHAR(16) NOT NULL COMMENT 'WECHAT/ALIPAY',
    legal_form VARCHAR(16) NOT NULL DEFAULT 'MICRO' COMMENT 'MICRO/INDIVIDUAL/ENTERPRISE',
    sub_mchid VARCHAR(64) DEFAULT NULL COMMENT '二级商户号，进件成功后由通道回执回填',
    channel_apply_no VARCHAR(64) DEFAULT NULL COMMENT '通道侧的进件申请单号',
    apply_status VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/APPLYING/ACTIVE/REJECTED/FROZEN',
    reject_reason VARCHAR(512) DEFAULT NULL COMMENT '驳回原因，原样给商家看',
    pay_methods JSON DEFAULT NULL COMMENT '["JSAPI","APP","H5","NATIVE"]，通道回执写入',
    invoice_capable TINYINT(4) NOT NULL DEFAULT 0 COMMENT '能否开票',
    settle_account_type VARCHAR(24) DEFAULT NULL COMMENT 'PERSONAL_BANK/CORPORATE_BANK',
    settle_account_masked VARCHAR(64) DEFAULT NULL,
    fee_bearer VARCHAR(16) NOT NULL DEFAULT 'MERCHANT' COMMENT 'MERCHANT/PLATFORM',
    applied_at BIGINT(20) DEFAULT NULL,
    activated_at BIGINT(20) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    split_reversible TINYINT(4) NOT NULL DEFAULT 0 COMMENT '接收方是否已开启**分账回退**授权。为 0 时**售后期后的退款走不通** —— 已分账要先回退才能退款。进件/授权回执写入',
    split_reversible_at BIGINT(20) DEFAULT NULL COMMENT '授权开启时间。在此之前成交的单若已分账，同样退不了 —— 排查时要看这个时点',
    PRIMARY KEY (id),
    UNIQUE KEY uk_mp_entity_channel (entity_no,pay_channel),
    UNIQUE KEY uk_mp_pay_merchant_no (pay_merchant_no),
    KEY idx_mp_sub_mchid (sub_mchid),
    KEY idx_mp_status (apply_status,pay_channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='收款商户号：进件产物 sub_mchid，每主体×每通道一条。全库唯一合法的 merchant 用法';

CREATE TABLE IF NOT EXISTS mch_account
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    mch_account_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) DEFAULT NULL COMMENT '可选关联的 C 端账号，小程序免登用。**可空** —— 解绑 C 端不影响他上班',
    is_owner TINYINT(4) NOT NULL DEFAULT 0 COMMENT '老板：**全主体全门店**，不需要逐店授权行',
    is_primary TINYINT(4) NOT NULL DEFAULT 0 COMMENT '该用户的默认主体。App 多商家切换用；小程序恒取它',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    login_phone VARCHAR(32) DEFAULT NULL COMMENT '员工自己的登录手机号（App 走这条，独立于 C 端账号池）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_mch_account_no (mch_account_no),
    UNIQUE KEY uk_mch_account_entity_user (entity_no,user_no),
    UNIQUE KEY uk_mch_account_entity_phone (entity_no,login_phone),
    KEY idx_ms_user (user_no,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='商家账号：B 端登录账号（login_phone 注册，与消费者账号彻底独立）。当前一行同时承载对主体的成员关系，门店角色见 mch_store_role';

CREATE TABLE IF NOT EXISTS mch_store_role
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    mch_account_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL COMMENT 'MANAGER（店长）/ CLERK（店员）。OWNER 不在这里 —— 他不需要逐店授权',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_store_role (mch_account_no,store_no),
    KEY idx_ss_store (store_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='子账号在各门店的角色（每店一个角色）';

CREATE TABLE IF NOT EXISTS mch_store
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    announcement VARCHAR(255) DEFAULT NULL COMMENT '店铺公告，如「今天到了新米和土鸡蛋」',
    open_hours VARCHAR(64) DEFAULT NULL COMMENT '营业时间文案，店主自填（不做结构化：早市摊位的作息没法枚举）',
    address VARCHAR(255) DEFAULT NULL COMMENT '店铺地址，店主自填',
    featured TEXT DEFAULT NULL COMMENT 'JSON 数组：主推商品 goods_no，有序',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    store_no VARCHAR(64) NOT NULL COMMENT '门店业务键',
    name VARCHAR(128) DEFAULT NULL COMMENT '门店名。可与主体名不同（「张记粮油·文三路店」）',
    is_default TINYINT(4) NOT NULL DEFAULT 0 COMMENT '默认门店。一主体恰好一个，删不掉 —— 它是单店商家的全部',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / SUSPENDED / READONLY（Plan 降级：不接新单，但未完成的单照常核销）',
    pay_merchant_no VARCHAR(64) DEFAULT NULL COMMENT '这家店用哪个收款商户号（mch_payment_merchant.pay_merchant_no）。**为空 = 用主体的默认商户号** —— 单通道时永远为空，行为与今天一致',
    payment_changed_at BIGINT(20) DEFAULT NULL COMMENT '最近一次切换收款商户号的时间；配合审计日志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_store_no (store_no),
    KEY idx_store_entity (entity_no,is_default),
    KEY idx_store_payment (pay_merchant_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='门店：顾客感知的边界（地址/营业时间/库存/评价/履约）。关联主体可切换（换执照店照开）；每主体恰好一个默认店';

CREATE TABLE IF NOT EXISTS usr_store_favorite
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_entity (user_no,entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='收藏的店';

CREATE TABLE IF NOT EXISTS usr_account
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_no VARCHAR(64) NOT NULL,
    nickname VARCHAR(64) DEFAULT NULL,
    avatar VARCHAR(512) DEFAULT NULL,
    phone VARCHAR(32) DEFAULT NULL,
    openid VARCHAR(64) DEFAULT NULL,
    unionid VARCHAR(64) DEFAULT NULL,
    apple_sub VARCHAR(128) DEFAULT NULL,
    community_no VARCHAR(64) DEFAULT NULL,
    pickup_no VARCHAR(64) DEFAULT NULL,
    entity_no VARCHAR(64) DEFAULT NULL COMMENT '常去店（进店归因 C-ST-09）',
    status VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_no (user_no),
    UNIQUE KEY uk_openid (openid),
    UNIQUE KEY uk_phone (phone),
    UNIQUE KEY uk_apple_sub (apple_sub)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='消费者账号：与商家账号 mch_account 彻底独立，不关联';
