-- 【自动生成，勿手改】由 backend/scripts/gen-test-schema.py 重放 db/migration/V*.sql 得到。
-- 生产是 MySQL 方言；这份是 H2 等价物（去列注释与普通索引，UNIQUE 转 CONSTRAINT）。
-- 与源文件的漂移由 SchemaDriftTest 拦截。


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
    city_code VARCHAR(32) DEFAULT NULL,
    points_enabled TINYINT(4) NOT NULL DEFAULT 0,
    grid VARCHAR(64) DEFAULT NULL,
    fence_radius INT(11) NOT NULL DEFAULT 1000,
    region_code VARCHAR(12) DEFAULT NULL,
    kind VARCHAR(16) NOT NULL DEFAULT 'ESTATE',
    coords_source VARCHAR(16) DEFAULT NULL,
    origin_code VARCHAR(12) DEFAULT NULL,
    source VARCHAR(16) NULL,
    alias VARCHAR(128) NULL,
    CONSTRAINT uk_community_origin UNIQUE (origin_code),
    PRIMARY KEY (id),
    CONSTRAINT uk_community_no UNIQUE (community_no)
);

CREATE TABLE IF NOT EXISTS cmt_pickup_point
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    pickup_no VARCHAR(64) NOT NULL,
    community_no VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    address VARCHAR(255) DEFAULT NULL,
    lat_e6 INT(11) DEFAULT NULL,
    lng_e6 INT(11) DEFAULT NULL,
    type VARCHAR(16) NOT NULL DEFAULT 'STORE',
    scope VARCHAR(16) NOT NULL DEFAULT 'PERMANENT',
    owner_ref VARCHAR(64) DEFAULT NULL,
    group_no VARCHAR(64) DEFAULT NULL,
    open_hours VARCHAR(64) DEFAULT NULL,
    arrival_desc VARCHAR(128) DEFAULT NULL,
    service_fee_rate INT(11) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    time_slot VARCHAR(64) DEFAULT NULL,
    service_fee_per_item_minor BIGINT(20) NOT NULL DEFAULT 0,
    fee_mode VARCHAR(16) NOT NULL DEFAULT 'NONE',
    archived_at DATETIME DEFAULT NULL,
    reject_reason VARCHAR(255) NULL,
    adcode VARCHAR(12) NULL,
    address_verified TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_pickup_no UNIQUE (pickup_no),
    CONSTRAINT ck_neighbor_zero_fee CHECK (type <> 'NEIGHBOR' or service_fee_rate = 0)
);

CREATE TABLE IF NOT EXISTS ful_batch
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    batch_no VARCHAR(64) NOT NULL,
    pickup_no VARCHAR(64) NOT NULL,
    arrive_date VARCHAR(16) NOT NULL,
    total_qty INT(11) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'PLANNED',
    received_at BIGINT(20) DEFAULT NULL,
    received_by VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    community_no VARCHAR(64) DEFAULT NULL,
    plan_arrive_at BIGINT(20) DEFAULT NULL,
    vehicle VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_batch_no UNIQUE (batch_no)
);

CREATE TABLE IF NOT EXISTS ful_group_pickup
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    pickup_no VARCHAR(64) NOT NULL,
    group_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    address VARCHAR(255) NOT NULL,
    time_slot VARCHAR(64) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    received_at BIGINT(20) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_group_pickup_no UNIQUE (pickup_no),
    CONSTRAINT uk_group UNIQUE (group_no)
);

CREATE TABLE IF NOT EXISTS ful_verify_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    sub_order_no VARCHAR(64) DEFAULT NULL,
    pickup_no VARCHAR(64) NOT NULL,
    verify_code VARCHAR(16) NOT NULL,
    verify_type VARCHAR(16) NOT NULL DEFAULT 'SCAN',
    operator_no VARCHAR(64) NOT NULL,
    result VARCHAR(24) NOT NULL,
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS mkt_attribution
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL,
    inviter_no VARCHAR(64) DEFAULT NULL,
    channel VARCHAR(64) DEFAULT NULL,
    source VARCHAR(16) NOT NULL,
    expire_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_user UNIQUE (user_no)
);

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
    prev_ref VARCHAR(64) DEFAULT NULL,
    reason VARCHAR(128) DEFAULT NULL,
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    trace_no VARCHAR(64) DEFAULT NULL,
    device_id VARCHAR(64) DEFAULT NULL,
    ip VARCHAR(64) DEFAULT NULL,
    order_no VARCHAR(64) DEFAULT NULL,
    risk_signals VARCHAR(255) DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS mkt_campaign
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    campaign_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    start_at BIGINT(20) NOT NULL,
    end_at BIGINT(20) NOT NULL,
    threshold_minor BIGINT(20) DEFAULT NULL,
    discount_minor BIGINT(20) DEFAULT NULL,
    flash_price_minor BIGINT(20) DEFAULT NULL,
    buy_n INT(11) DEFAULT NULL,
    gift_m INT(11) DEFAULT NULL,
    goods_nos TEXT DEFAULT NULL,
    total_count INT(11) DEFAULT NULL,
    taken_count INT(11) NOT NULL DEFAULT 0,
    used_count INT(11) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    store_no VARCHAR(64) DEFAULT NULL,
    archived_at DATETIME DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_campaign_no UNIQUE (campaign_no)
);

CREATE TABLE IF NOT EXISTS mkt_coupon
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    coupon_no VARCHAR(64) NOT NULL,
    title VARCHAR(128) NOT NULL,
    type VARCHAR(16) NOT NULL,
    face_minor BIGINT(20) NOT NULL DEFAULT 0,
    discount_rate INT(11) NOT NULL DEFAULT 0,
    threshold_minor BIGINT(20) NOT NULL DEFAULT 0,
    max_discount_minor BIGINT(20) NOT NULL DEFAULT 0,
    funder VARCHAR(16) NOT NULL DEFAULT 'PLATFORM',
    entity_no VARCHAR(64) DEFAULT NULL,
    total_count INT(11) NOT NULL DEFAULT 0,
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
    scope_desc VARCHAR(255) DEFAULT NULL,
    budget_minor BIGINT(20) NOT NULL DEFAULT 0,
    archived_at DATETIME DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_coupon_no UNIQUE (coupon_no)
);

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
    min_count INT(11) NOT NULL DEFAULT 2,
    joined_count INT(11) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    end_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    pickup_no VARCHAR(64) DEFAULT NULL,
    initiator_user_no VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_group_no UNIQUE (group_no)
);

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
    CONSTRAINT uk_group_user UNIQUE (group_no,user_no)
);

CREATE TABLE IF NOT EXISTS mkt_quote
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    quote_no VARCHAR(64) NOT NULL,
    request_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    unit_price_minor BIGINT(20) NOT NULL,
    min_qty INT(11) NOT NULL DEFAULT 1,
    note VARCHAR(512) DEFAULT NULL,
    valid_until BIGINT(20) NOT NULL,
    revision_count INT(11) NOT NULL DEFAULT 0,
    chosen TINYINT(4) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_quote_no UNIQUE (quote_no),
    CONSTRAINT uk_request_entity UNIQUE (request_no,entity_no)
);

CREATE TABLE IF NOT EXISTS mkt_quote_revision
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    quote_no VARCHAR(64) NOT NULL,
    request_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    from_price_minor BIGINT(20) NOT NULL,
    to_price_minor BIGINT(20) NOT NULL,
    raised TINYINT(4) NOT NULL DEFAULT 0,
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS mkt_request
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    request_no VARCHAR(64) NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT DEFAULT NULL,
    images TEXT DEFAULT NULL,
    expect_count INT(11) NOT NULL DEFAULT 1,
    interest_count INT(11) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'COLLECTING',
    chosen_quote_no VARCHAR(64) DEFAULT NULL,
    locked_price BIGINT(20) DEFAULT NULL,
    end_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    pickup_no VARCHAR(64) DEFAULT NULL,
    budget_minor BIGINT(20) DEFAULT NULL,
    group_no VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_request_no UNIQUE (request_no)
);

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
    CONSTRAINT uk_request_user UNIQUE (request_no,user_no)
);

CREATE TABLE IF NOT EXISTS mkt_user_coupon
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_coupon_no VARCHAR(64) NOT NULL,
    coupon_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UNUSED',
    order_no VARCHAR(64) DEFAULT NULL,
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
    CONSTRAINT uk_user_coupon_no UNIQUE (user_coupon_no)
);

CREATE TABLE IF NOT EXISTS notify_message
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    message_no VARCHAR(64) NOT NULL,
    receiver_no VARCHAR(64) NOT NULL,
    msg_type VARCHAR(16) NOT NULL,
    title VARCHAR(128) NOT NULL,
    body VARCHAR(512) DEFAULT NULL,
    link VARCHAR(255) DEFAULT NULL,
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
    template_no VARCHAR(64) DEFAULT NULL,
    receiver_type VARCHAR(16) NOT NULL DEFAULT 'USER',
    PRIMARY KEY (id),
    CONSTRAINT uk_message_no UNIQUE (message_no),
    CONSTRAINT uk_msg_dedup UNIQUE (dedup_key)
);

CREATE TABLE IF NOT EXISTS notify_subscribe
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
    quota INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_sub_user_template UNIQUE (user_no,template_id)
);

CREATE TABLE IF NOT EXISTS notify_ticket
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
    replied_by VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_ticket_no UNIQUE (ticket_no)
);

CREATE TABLE IF NOT EXISTS ord_after_sale
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    after_sale_no VARCHAR(64) NOT NULL,
    sub_order_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'APPLIED',
    reason VARCHAR(255) NOT NULL,
    images TEXT DEFAULT NULL,
    refund_minor BIGINT(20) NOT NULL DEFAULT 0,
    instant TINYINT(4) NOT NULL DEFAULT 0,
    merchant_remark VARCHAR(255) DEFAULT NULL,
    express_company VARCHAR(64) DEFAULT NULL,
    express_no VARCHAR(64) DEFAULT NULL,
    liability VARCHAR(16) DEFAULT NULL,
    split_reversed TINYINT(4) NOT NULL DEFAULT 0,
    refunded_at BIGINT(20) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    dispute_reason VARCHAR(512) DEFAULT NULL,
    points_offset_minor BIGINT(20) NOT NULL DEFAULT 0,
    refund_payment_no VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_after_sale_no UNIQUE (after_sale_no)
);

CREATE TABLE IF NOT EXISTS ord_item
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    sub_order_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    goods_no VARCHAR(64) NOT NULL,
    sku_no VARCHAR(64) NOT NULL,
    title VARCHAR(255) DEFAULT NULL,
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
    nominal_gram INT(11) DEFAULT NULL,
    weighed TINYINT(4) NOT NULL DEFAULT 0,
    is_gift TINYINT(4) NOT NULL DEFAULT 0,
    weigh_adjust_minor BIGINT(20) NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

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
    pay_trade_no VARCHAR(64) DEFAULT NULL,
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
    pay_scene VARCHAR(16) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_no UNIQUE (order_no)
);

CREATE TABLE IF NOT EXISTS ord_status_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    sub_order_no VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    label VARCHAR(64) DEFAULT NULL,
    operator_type VARCHAR(16) NOT NULL DEFAULT 'SYSTEM',
    operator_no VARCHAR(64) DEFAULT NULL,
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id)
);

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
    traffic_source VARCHAR(24) DEFAULT NULL,
    goods_amount BIGINT(20) NOT NULL DEFAULT 0,
    freight_amount BIGINT(20) NOT NULL DEFAULT 0,
    discount_amount BIGINT(20) NOT NULL DEFAULT 0,
    pay_amount BIGINT(20) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'WAIT_PAY',
    verify_code VARCHAR(16) DEFAULT NULL,
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
    weigh_adjust_minor BIGINT(20) NOT NULL DEFAULT 0,
    appointment_at BIGINT(20) DEFAULT NULL,
    group_no VARCHAR(64) DEFAULT NULL,
    express_no VARCHAR(64) DEFAULT NULL,
    buyer_nickname VARCHAR(64) DEFAULT NULL,
    reviewed TINYINT(4) NOT NULL DEFAULT 0,
    points_deduct INT(11) NOT NULL DEFAULT 0,
    points_deduct_minor BIGINT(20) NOT NULL DEFAULT 0,
    points_granted TINYINT(4) NOT NULL DEFAULT 0,
    points_fee_minor BIGINT(20) NOT NULL DEFAULT 0,
    merchant_recv_minor BIGINT(20) NOT NULL DEFAULT 0,
    channel_fee_minor BIGINT(20) NOT NULL DEFAULT 0,
    commission_minor BIGINT(20) NOT NULL DEFAULT 0,
    service_fee_minor BIGINT(20) NOT NULL DEFAULT 0,
    store_no VARCHAR(64) DEFAULT NULL,
    require_buyer_confirm TINYINT(4) NOT NULL DEFAULT 0,
    receiver_name VARCHAR(64) DEFAULT NULL,
    receiver_phone VARCHAR(32) DEFAULT NULL,
    receiver_address VARCHAR(255) DEFAULT NULL,
    community_no VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sub_order_no UNIQUE (sub_order_no),
    CONSTRAINT uk_verify_code UNIQUE (verify_code)
);

CREATE TABLE IF NOT EXISTS prd_category
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    category_no VARCHAR(64) NOT NULL,
    parent_no VARCHAR(64) DEFAULT NULL,
    level INT(11) NOT NULL DEFAULT 1,
    name VARCHAR(64) NOT NULL,
    icon VARCHAR(512) DEFAULT NULL,
    sort INT(11) NOT NULL DEFAULT 0,
    attr_template TEXT DEFAULT NULL,
    qualification_required VARCHAR(512) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    template VARCHAR(16) NOT NULL DEFAULT 'STANDARD',
    required_code VARCHAR(32) DEFAULT NULL,
    name_en VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_category_no UNIQUE (category_no)
);

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
    CONSTRAINT uk_community_goods UNIQUE (community_no,goods_no)
);

CREATE TABLE IF NOT EXISTS prd_goods
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    goods_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    subtitle VARCHAR(255) DEFAULT NULL,
    cover VARCHAR(512) DEFAULT NULL,
    images TEXT DEFAULT NULL,
    type VARCHAR(16) NOT NULL,
    category_no VARCHAR(64) DEFAULT NULL,
    fulfillments VARCHAR(255) DEFAULT NULL,
    spec_groups TEXT DEFAULT NULL,
    rating INT(11) NOT NULL DEFAULT 50,
    rating_count INT(11) NOT NULL DEFAULT 0,
    sales INT(11) NOT NULL DEFAULT 0,
    limit_per_user INT(11) NOT NULL DEFAULT 0,
    on_sale TINYINT(4) NOT NULL DEFAULT 0,
    audit_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
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
    points_config INT(11) DEFAULT NULL,
    group_price_minor BIGINT(20) DEFAULT NULL,
    group_min_count INT(11) DEFAULT NULL,
    sellable_override TEXT DEFAULT NULL,
    title_i18n TEXT DEFAULT NULL,
    subtitle_i18n TEXT DEFAULT NULL,
    audit_reason VARCHAR(512) DEFAULT NULL,
    std_no VARCHAR(64) DEFAULT NULL,
    detail TEXT DEFAULT NULL,
    detail_images TEXT DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_goods_no UNIQUE (goods_no)
);

CREATE TABLE IF NOT EXISTS prd_sku
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    sku_no VARCHAR(64) NOT NULL,
    goods_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    market VARCHAR(8) NOT NULL DEFAULT 'CN',
    option_values VARCHAR(512) DEFAULT NULL,
    spec VARCHAR(128) DEFAULT NULL,
    price BIGINT(20) NOT NULL,
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
    presale_quota INT(11) NOT NULL DEFAULT 0,
    sold_count INT(11) NOT NULL DEFAULT 0,
    cutoff_at DATETIME DEFAULT NULL,
    arrive_at DATETIME DEFAULT NULL,
    cost_price BIGINT(20) DEFAULT NULL,
    option_value_nos VARCHAR(512) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_entity_sku_market UNIQUE (entity_no,sku_no,market)
);

CREATE TABLE IF NOT EXISTS prd_spec_template
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    template_no VARCHAR(64) NOT NULL,
    scope VARCHAR(16) NOT NULL DEFAULT 'MERCHANT',
    category_type VARCHAR(16) DEFAULT NULL,
    name VARCHAR(64) NOT NULL,
    options TEXT NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    category_no VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_template_no UNIQUE (template_no)
);

CREATE TABLE IF NOT EXISTS prd_stock_lock
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    lock_no VARCHAR(64) NOT NULL,
    sku_no VARCHAR(64) NOT NULL,
    qty INT(11) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'LOCKED',
    locked_at DATETIME NOT NULL,
    settled_at DATETIME DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    store_no VARCHAR(64) DEFAULT NULL,
    presale TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS pts_user_account
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_no VARCHAR(64) NOT NULL,
    balance BIGINT(20) NOT NULL DEFAULT 0,
    total_earn BIGINT(20) NOT NULL DEFAULT 0,
    total_use BIGINT(20) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    market VARCHAR(8) NOT NULL DEFAULT 'CN',
    pending_balance BIGINT(20) NOT NULL DEFAULT 0,
    expire_at BIGINT(20) DEFAULT NULL,
    last_active_at BIGINT(20) DEFAULT NULL,
    expire_notified_at BIGINT(20) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_pts_account_user_market UNIQUE (user_no,market)
);

CREATE TABLE IF NOT EXISTS pts_user_ledger
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    ledger_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    biz_type VARCHAR(16) NOT NULL,
    points BIGINT(20) NOT NULL,
    balance_after BIGINT(20) NOT NULL,
    issuer_merchant_no VARCHAR(64) DEFAULT NULL,
    sub_order_no VARCHAR(64) DEFAULT NULL,
    remark VARCHAR(255) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    available_at BIGINT(20) DEFAULT NULL,
    market VARCHAR(8) NOT NULL DEFAULT 'CN',
    acceptor_merchant_no VARCHAR(64) DEFAULT NULL,
    amount_minor BIGINT(20) DEFAULT NULL,
    rate_snapshot INT(11) DEFAULT NULL,
    status VARCHAR(16) DEFAULT NULL,
    period VARCHAR(8) DEFAULT NULL,
    confirmed_at BIGINT(20) DEFAULT NULL,
    currency VARCHAR(8) DEFAULT NULL,
    activated_at BIGINT(20) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_pts_ledger_no UNIQUE (ledger_no)
);

CREATE TABLE IF NOT EXISTS rvw_appeal
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    appeal_no VARCHAR(64) NOT NULL,
    review_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    images TEXT DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    submitted_at BIGINT(20) NOT NULL,
    verdict VARCHAR(512) DEFAULT NULL,
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
    CONSTRAINT uk_appeal_no UNIQUE (appeal_no),
    CONSTRAINT uk_review UNIQUE (review_no)
);

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
    rating TINYINT(4) NOT NULL,
    score_goods TINYINT(4) DEFAULT NULL,
    score_fulfillment TINYINT(4) DEFAULT NULL,
    score_service TINYINT(4) DEFAULT NULL,
    content VARCHAR(1024) DEFAULT NULL,
    images TEXT DEFAULT NULL,
    spec VARCHAR(255) DEFAULT NULL,
    like_count INT(11) NOT NULL DEFAULT 0,
    reply VARCHAR(512) DEFAULT NULL,
    replied_at BIGINT(20) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PASSED',
    reject_reason VARCHAR(255) DEFAULT NULL,
    risk_flags TEXT DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    store_no VARCHAR(32) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_review_no UNIQUE (review_no),
    CONSTRAINT uk_order_goods UNIQUE (sub_order_no,goods_no)
);

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
    CONSTRAINT uk_review_user UNIQUE (review_no,user_no)
);

CREATE TABLE IF NOT EXISTS stl_bill
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    settle_no VARCHAR(64) NOT NULL,
    sub_order_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    gross_minor BIGINT(20) NOT NULL,
    commission_minor BIGINT(20) NOT NULL DEFAULT 0,
    service_fee_minor BIGINT(20) NOT NULL DEFAULT 0,
    net_minor BIGINT(20) NOT NULL DEFAULT 0,
    traffic_source VARCHAR(24) DEFAULT NULL,
    commission_rate INT(11) NOT NULL DEFAULT 0,
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
    pay_channel VARCHAR(16) NOT NULL DEFAULT 'WECHAT',
    pay_scene VARCHAR(16) DEFAULT NULL,
    channel_fee_minor BIGINT(20) NOT NULL DEFAULT 0,
    channel_fee_rate INT(11) NOT NULL DEFAULT 0,
    channel_fee_source VARCHAR(16) DEFAULT NULL,
    fee_bearer VARCHAR(16) NOT NULL DEFAULT 'MERCHANT',
    points_fee_minor BIGINT(20) NOT NULL DEFAULT 0,
    accrued_at BIGINT(20) DEFAULT NULL,
    split_amount_minor BIGINT(20) NOT NULL DEFAULT 0,
    store_no VARCHAR(64) DEFAULT NULL,
    pay_merchant_no VARCHAR(64) DEFAULT NULL,
    business_mode VARCHAR(16) NOT NULL DEFAULT 'SELF_OPERATED',
    payment_ref VARCHAR(64) DEFAULT NULL,
    paid_at BIGINT(20) DEFAULT NULL,
    purchase_invoice_no VARCHAR(64) DEFAULT NULL,
    invoice_status VARCHAR(16) NOT NULL DEFAULT 'PENDING_INVOICE',
    subsidy_minor BIGINT(20) NOT NULL DEFAULT 0,
    subsidy_at BIGINT(20) DEFAULT NULL,
    funds_mode VARCHAR(16) NOT NULL DEFAULT 'AGGREGATED',
    points_cost_minor BIGINT(20) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_settle_no UNIQUE (settle_no),
    CONSTRAINT uk_sub_order UNIQUE (sub_order_no)
);

CREATE TABLE IF NOT EXISTS stl_payment
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    payment_no VARCHAR(64) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    sub_order_no VARCHAR(64) DEFAULT NULL,
    after_sale_no VARCHAR(64) DEFAULT NULL,
    user_no VARCHAR(64) NOT NULL,
    pay_channel VARCHAR(16) NOT NULL,
    pay_scene VARCHAR(16) DEFAULT NULL,
    pay_method VARCHAR(16) DEFAULT NULL,
    amount_minor BIGINT(20) NOT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
    status VARCHAR(16) NOT NULL DEFAULT 'INIT',
    out_trade_no VARCHAR(64) DEFAULT NULL,
    trade_no VARCHAR(64) DEFAULT NULL,
    channel_fee_minor BIGINT(20) NOT NULL DEFAULT 0,
    succeeded_at BIGINT(20) DEFAULT NULL,
    closed_at BIGINT(20) DEFAULT NULL,
    err_code VARCHAR(64) DEFAULT NULL,
    err_msg VARCHAR(255) DEFAULT NULL,
    raw_notify TEXT DEFAULT NULL,
    reconciled_at BIGINT(20) DEFAULT NULL,
    reconcile_batch VARCHAR(32) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    entity_no VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_stl_payment_no UNIQUE (payment_no),
    CONSTRAINT uk_stl_payment_trade UNIQUE (pay_channel,trade_no)
);

CREATE TABLE IF NOT EXISTS stl_points_pool
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    flow_no VARCHAR(64) NOT NULL,
    direction VARCHAR(8) NOT NULL,
    pool_type VARCHAR(24) NOT NULL,
    amount_minor BIGINT(20) NOT NULL,
    balance_after BIGINT(20) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL,
    period VARCHAR(8) DEFAULT NULL,
    ref_no VARCHAR(64) DEFAULT NULL,
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
    pay_channel VARCHAR(16) NOT NULL DEFAULT 'WECHAT',
    PRIMARY KEY (id),
    CONSTRAINT uk_pts_pool_flow_no UNIQUE (flow_no)
);

CREATE TABLE IF NOT EXISTS stl_split_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    settle_no VARCHAR(64) NOT NULL,
    sub_order_no VARCHAR(64) NOT NULL,
    split_action VARCHAR(16) NOT NULL,
    amount_minor BIGINT(20) NOT NULL,
    request_no VARCHAR(64) NOT NULL,
    result VARCHAR(16) NOT NULL,
    provider_no VARCHAR(64) DEFAULT NULL,
    message VARCHAR(512) DEFAULT NULL,
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_split_request_no UNIQUE (request_no)
);

CREATE TABLE IF NOT EXISTS sys_audit_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    staff_no VARCHAR(64) NOT NULL,
    staff_name VARCHAR(64) DEFAULT NULL,
    op_action VARCHAR(64) NOT NULL,
    target VARCHAR(128) DEFAULT NULL,
    detail VARCHAR(512) DEFAULT NULL,
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    ip VARCHAR(64) DEFAULT NULL,
    client_type VARCHAR(16) DEFAULT NULL,
    critical TINYINT(1) NOT NULL DEFAULT 0,
    before_json TEXT DEFAULT NULL,
    after_json TEXT DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS sys_channel_category_rule
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    scene VARCHAR(16) NOT NULL,
    category_type VARCHAR(16) NOT NULL,
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
    CONSTRAINT uk_ccr_scene_category UNIQUE (scene,category_type)
);

CREATE TABLE IF NOT EXISTS sys_idempotent
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    idem_key VARCHAR(128) NOT NULL,
    endpoint VARCHAR(128) NOT NULL,
    user_no VARCHAR(64) DEFAULT NULL,
    result_json TEXT DEFAULT NULL,
    created_at DATETIME NOT NULL,
    expire_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_key_endpoint UNIQUE (idem_key,endpoint)
);

CREATE TABLE IF NOT EXISTS sys_industry
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    industry VARCHAR(24) NOT NULL,
    name VARCHAR(32) NOT NULL,
    sort INT(11) NOT NULL DEFAULT 0,
    enabled TINYINT(4) NOT NULL DEFAULT 1,
    wechat_micro_allowed TINYINT(4) NOT NULL DEFAULT 0,
    alipay_micro_allowed TINYINT(4) NOT NULL DEFAULT 0,
    points_forced TINYINT(4) NOT NULL DEFAULT 0,
    remark VARCHAR(255) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_industry UNIQUE (industry)
);

CREATE TABLE IF NOT EXISTS sys_legal_form
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    legal_form VARCHAR(24) NOT NULL,
    name VARCHAR(32) NOT NULL,
    sort INT(11) NOT NULL DEFAULT 0,
    enabled TINYINT(4) NOT NULL DEFAULT 1,
    legacy_subject VARCHAR(24) DEFAULT NULL,
    wechat_code VARCHAR(32) DEFAULT NULL,
    alipay_code VARCHAR(32) DEFAULT NULL,
    need_license TINYINT(4) NOT NULL DEFAULT 1,
    settle_account_type VARCHAR(24) DEFAULT NULL,
    industry_gated TINYINT(4) NOT NULL DEFAULT 0,
    remark VARCHAR(255) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_legal_form UNIQUE (legal_form)
);

CREATE TABLE IF NOT EXISTS sys_outbox
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    event_no VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT(11) NOT NULL DEFAULT 0,
    next_retry_at DATETIME DEFAULT NULL,
    last_error VARCHAR(512) DEFAULT NULL,
    created_at DATETIME NOT NULL,
    sent_at DATETIME DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_event_no UNIQUE (event_no)
);

CREATE TABLE IF NOT EXISTS sys_pay_channel
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    pay_channel VARCHAR(16) NOT NULL,
    name VARCHAR(32) NOT NULL,
    enabled TINYINT(4) NOT NULL DEFAULT 1,
    supports_subsidy TINYINT(4) NOT NULL DEFAULT 0,
    supports_split TINYINT(4) NOT NULL DEFAULT 1,
    supports_payout TINYINT(4) NOT NULL DEFAULT 0,
    pay_methods TEXT DEFAULT NULL,
    markets TEXT DEFAULT NULL,
    pool_account_ref VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    max_partial_refunds INT(11) NOT NULL DEFAULT 0,
    refund_interval_seconds INT(11) NOT NULL DEFAULT 0,
    max_split_rate INT(11) NOT NULL DEFAULT 10000,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_pay_channel UNIQUE (pay_channel)
);

CREATE TABLE IF NOT EXISTS sys_ops_staff
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    staff_no VARCHAR(64) NOT NULL,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(128) NOT NULL,
    real_name VARCHAR(64) DEFAULT NULL,
    roles VARCHAR(255) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    merchant_no VARCHAR(64) DEFAULT NULL,
    community_no VARCHAR(64) DEFAULT NULL,
    pickup_no VARCHAR(64) DEFAULT NULL,
    last_login_at BIGINT(20) DEFAULT NULL,
    must_change_password TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_ops_staff_no UNIQUE (staff_no),
    CONSTRAINT uk_ops_staff_username UNIQUE (username)
);

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
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS usr_address
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    address_id VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    province VARCHAR(64) DEFAULT NULL,
    city VARCHAR(64) DEFAULT NULL,
    district VARCHAR(64) DEFAULT NULL,
    detail VARCHAR(255) NOT NULL,
    lat_e6 INT(11) DEFAULT NULL,
    lng_e6 INT(11) DEFAULT NULL,
    is_default TINYINT(4) NOT NULL DEFAULT 0,
    tag VARCHAR(16) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    region VARCHAR(96) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_address_id UNIQUE (address_id)
);

CREATE TABLE IF NOT EXISTS mch_entity
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    logo VARCHAR(512) DEFAULT NULL,
    legal_form VARCHAR(16) DEFAULT NULL,
    tier VARCHAR(16) DEFAULT NULL,
    description VARCHAR(512) DEFAULT NULL,
    owner_user_no VARCHAR(64) DEFAULT NULL,
    rating INT(11) NOT NULL DEFAULT 50,
    rating_count INT(11) NOT NULL DEFAULT 0,
    sales_count INT(11) NOT NULL DEFAULT 0,
    goods_count INT(11) NOT NULL DEFAULT 0,
    score_goods INT(11) NOT NULL DEFAULT 50,
    score_service INT(11) NOT NULL DEFAULT 50,
    score_speed INT(11) NOT NULL DEFAULT 50,
    verified TINYINT(4) NOT NULL DEFAULT 0,
    breach_count INT(11) NOT NULL DEFAULT 0,
    tags VARCHAR(512) DEFAULT NULL,
    joined_at BIGINT(20) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    store_code VARCHAR(32) DEFAULT NULL,
    service_scope VARCHAR(16) NOT NULL DEFAULT 'COMMUNITY',
    service_city_code VARCHAR(32) DEFAULT NULL,
    points_enabled TINYINT(4) NOT NULL DEFAULT 0,
    points_forced TINYINT(4) NOT NULL DEFAULT 0,
    industry VARCHAR(24) DEFAULT NULL,
    category_codes VARCHAR(512) DEFAULT NULL,
    archived_at DATETIME DEFAULT NULL,
    fulfillment_reach VARCHAR(16) NOT NULL DEFAULT 'PICKUP',
    funds_mode VARCHAR(16) NOT NULL DEFAULT 'AGGREGATED',
    is_agri_producer TINYINT NOT NULL DEFAULT 0,
    biz_qualification VARCHAR(16) NOT NULL DEFAULT 'UNREGISTERED',
    exempt_type VARCHAR(24) DEFAULT NULL,
    acode_base64 MEDIUMTEXT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_entity_no UNIQUE (entity_no),
    CONSTRAINT uk_store_code UNIQUE (store_code)
);

CREATE TABLE IF NOT EXISTS mch_entity_apply
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    apply_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL,
    name VARCHAR(128) NOT NULL,
    legal_form VARCHAR(16) DEFAULT NULL,
    contact_phone VARCHAR(32) DEFAULT NULL,
    qualifications TEXT DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
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
    contact_name VARCHAR(64) DEFAULT NULL,
    category VARCHAR(64) DEFAULT NULL,
    description VARCHAR(512) DEFAULT NULL,
    service_scope VARCHAR(16) NOT NULL DEFAULT 'COMMUNITY',
    community_nos VARCHAR(1024) DEFAULT NULL,
    active_owner VARCHAR(64) DEFAULT NULL,
    as_pickup_point TINYINT(4) NOT NULL DEFAULT 0,
    industry VARCHAR(24) DEFAULT NULL,
    qualification_items TEXT DEFAULT NULL,
    category_codes TEXT DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_apply_no UNIQUE (apply_no),
    CONSTRAINT uk_apply_active_owner UNIQUE (active_owner)
);

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
    CONSTRAINT uk_entity_community UNIQUE (entity_no,community_no)
);

CREATE TABLE IF NOT EXISTS mch_payment_merchant
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    pay_merchant_no VARCHAR(64) DEFAULT NULL,
    pay_channel VARCHAR(16) NOT NULL,
    legal_form VARCHAR(16) NOT NULL DEFAULT 'MICRO',
    sub_mchid VARCHAR(64) DEFAULT NULL,
    channel_apply_no VARCHAR(64) DEFAULT NULL,
    apply_status VARCHAR(16) NOT NULL DEFAULT 'NONE',
    reject_reason VARCHAR(512) DEFAULT NULL,
    pay_methods TEXT DEFAULT NULL,
    invoice_capable TINYINT(4) NOT NULL DEFAULT 0,
    settle_account_type VARCHAR(24) DEFAULT NULL,
    settle_account_masked VARCHAR(64) DEFAULT NULL,
    fee_bearer VARCHAR(16) NOT NULL DEFAULT 'MERCHANT',
    applied_at BIGINT(20) DEFAULT NULL,
    activated_at BIGINT(20) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    split_reversible TINYINT(4) NOT NULL DEFAULT 0,
    split_reversible_at BIGINT(20) DEFAULT NULL,
    store_no VARCHAR(64) NOT NULL DEFAULT '',
    quota_limit_minor BIGINT(20) NOT NULL DEFAULT 0,
    quota_used_minor BIGINT(20) NOT NULL DEFAULT 0,
    quota_period VARCHAR(16) DEFAULT NULL,
    CONSTRAINT uk_mp_entity_channel_store UNIQUE (entity_no,pay_channel,store_no),
    PRIMARY KEY (id),
    CONSTRAINT uk_mp_pay_merchant_no UNIQUE (pay_merchant_no)
);

CREATE TABLE IF NOT EXISTS mch_account
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    mch_account_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) DEFAULT NULL,
    is_owner TINYINT(4) NOT NULL DEFAULT 0,
    is_primary TINYINT(4) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    login_phone VARCHAR(32) DEFAULT NULL,
    display_name VARCHAR(32) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_mch_account_no UNIQUE (mch_account_no),
    CONSTRAINT uk_mch_account_entity_user UNIQUE (entity_no,user_no),
    CONSTRAINT uk_mch_account_entity_phone UNIQUE (entity_no,login_phone)
);

CREATE TABLE IF NOT EXISTS mch_store_role
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    mch_account_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    CONSTRAINT uk_store_role UNIQUE (mch_account_no,store_no,role),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS mch_store
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    announcement VARCHAR(255) DEFAULT NULL,
    open_hours VARCHAR(64) DEFAULT NULL,
    address VARCHAR(255) DEFAULT NULL,
    featured TEXT DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    store_no VARCHAR(64) NOT NULL,
    name VARCHAR(128) DEFAULT NULL,
    is_default TINYINT(4) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    pay_merchant_no VARCHAR(64) DEFAULT NULL,
    payment_changed_at BIGINT(20) DEFAULT NULL,
    delivery_radius_m INT(11) NOT NULL DEFAULT 3000,
    delivery_min_order_minor BIGINT(20) NOT NULL DEFAULT 0,
    delivery_fee_minor BIGINT(20) NOT NULL DEFAULT 0,
    delivery_free_threshold_minor BIGINT(20) NOT NULL DEFAULT 0,
    business_mode VARCHAR(16) NOT NULL DEFAULT 'SELF_OPERATED',
    plan_suspended TINYINT NOT NULL DEFAULT 0,
    rating INT(11) NOT NULL DEFAULT 0,
    rating_count INT(11) NOT NULL DEFAULT 0,
    score_goods INT(11) NOT NULL DEFAULT 0,
    score_service INT(11) NOT NULL DEFAULT 0,
    score_speed INT(11) NOT NULL DEFAULT 0,
    lat_e6 INT NULL,
    lng_e6 INT NULL,
    adcode VARCHAR(12) NULL,
    address_verified TINYINT(1) NOT NULL DEFAULT 0,
    address_detail VARCHAR(64) NULL,
    announcement_until BIGINT(20) NULL,
    announcement_recent VARCHAR(512) NULL,
    announcement_at BIGINT(20) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_store_no UNIQUE (store_no)
);

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
    CONSTRAINT uk_user_entity UNIQUE (user_no,entity_no)
);

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
    entity_no VARCHAR(64) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_no UNIQUE (user_no),
    CONSTRAINT uk_openid UNIQUE (openid),
    CONSTRAINT uk_phone UNIQUE (phone),
    CONSTRAINT uk_apple_sub UNIQUE (apple_sub)
);

CREATE TABLE IF NOT EXISTS usr_identity
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_no VARCHAR(64) NOT NULL,
    identity_type VARCHAR(32) NOT NULL,
    identity_value VARCHAR(191) NOT NULL,
    channel VARCHAR(16) DEFAULT NULL,
    verified_at DATETIME DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_identity UNIQUE (identity_type, identity_value)
);

CREATE TABLE IF NOT EXISTS sys_auth_code
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    required_qualification VARCHAR(64) DEFAULT NULL,
    sort INT(11) NOT NULL DEFAULT 0,
    enabled TINYINT(4) NOT NULL DEFAULT 1,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    qual_type VARCHAR(32) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_auth_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS sys_setting
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    setting_key VARCHAR(64) NOT NULL,
    setting_value TEXT NOT NULL,
    remark VARCHAR(255) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_setting_key UNIQUE (setting_key)
);

CREATE TABLE IF NOT EXISTS mch_violation
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    violation_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL,
    action VARCHAR(16) NOT NULL,
    detail VARCHAR(1024) NOT NULL,
    operator_no VARCHAR(64) DEFAULT NULL,
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    store_no VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_violation_no UNIQUE (violation_no)
);

CREATE TABLE IF NOT EXISTS mch_store_audit
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    audit_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    content VARCHAR(1024) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    hits VARCHAR(512) DEFAULT NULL,
    submitted_at BIGINT(20) NOT NULL,
    reason VARCHAR(512) DEFAULT NULL,
    decided_at BIGINT(20) DEFAULT NULL,
    decided_by VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    ref_no VARCHAR(64) NULL,
    store_no VARCHAR(64) NULL,
    notice_until BIGINT(20) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_store_audit_no UNIQUE (audit_no)
);

CREATE TABLE IF NOT EXISTS prd_store_stock
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    store_no VARCHAR(64) NOT NULL,
    sku_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    stock INT(11) NOT NULL DEFAULT 0,
    locked_stock INT(11) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_by VARCHAR(64) DEFAULT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_store_sku UNIQUE (store_no,sku_no)
);

CREATE TABLE IF NOT EXISTS prd_store_goods
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    store_no VARCHAR(64) NOT NULL,
    goods_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    on_sale TINYINT(4) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_by VARCHAR(64) DEFAULT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    platform_suspended TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_store_goods UNIQUE (store_no,goods_no)
);

CREATE TABLE IF NOT EXISTS notify_template
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    template_no VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    content VARCHAR(1024) NOT NULL,
    provider_template_id VARCHAR(64) DEFAULT NULL,
    enabled TINYINT(4) NOT NULL DEFAULT 1,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    lang VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
    CONSTRAINT uk_msg_template_template_no_lang UNIQUE (template_no, lang),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS stl_purchase_invoice
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    invoice_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    period VARCHAR(16) NOT NULL,
    invoice_code VARCHAR(32) DEFAULT NULL,
    invoice_number VARCHAR(32) NOT NULL,
    invoice_type VARCHAR(16) NOT NULL DEFAULT 'GENERAL',
    title_name VARCHAR(128) NOT NULL,
    title_tax_no VARCHAR(32) DEFAULT NULL,
    amount_minor BIGINT(20) NOT NULL,
    tax_amount_minor BIGINT(20) NOT NULL DEFAULT 0,
    tax_rate INT(11) NOT NULL DEFAULT 0,
    invoice_date BIGINT(20) DEFAULT NULL,
    image_url VARCHAR(512) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED',
    verified_by VARCHAR(64) DEFAULT NULL,
    verified_at BIGINT(20) DEFAULT NULL,
    reject_reason VARCHAR(512) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_purchase_invoice_no UNIQUE (invoice_no),
    CONSTRAINT uk_invoice_number UNIQUE (invoice_code, invoice_number)
);

CREATE TABLE IF NOT EXISTS mkt_coupon_issue
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    issue_no VARCHAR(64) NOT NULL,
    coupon_no VARCHAR(64) NOT NULL,
    coupon_name VARCHAR(128) NOT NULL,
    target VARCHAR(16) NOT NULL,
    target_desc VARCHAR(255) DEFAULT NULL,
    user_no VARCHAR(64) DEFAULT NULL,
    issued_count INT(11) NOT NULL DEFAULT 0,
    amount_minor BIGINT(20) NOT NULL DEFAULT 0,
    operator_no VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_coupon_issue_no UNIQUE (issue_no)
);

CREATE TABLE IF NOT EXISTS mch_qualification
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    qual_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    qual_type VARCHAR(32) NOT NULL,
    qual_name VARCHAR(128) NOT NULL,
    qual_number VARCHAR(64) DEFAULT NULL,
    image_url VARCHAR(512) DEFAULT NULL,
    expire_at BIGINT(20) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'VALID',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_mch_qual_no UNIQUE (qual_no)
);

CREATE TABLE IF NOT EXISTS mch_admission_policy
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    legal_form VARCHAR(24) NOT NULL,
    required_deposit_minor BIGINT(20) NOT NULL DEFAULT 0,
    single_order_limit_minor BIGINT(20) NOT NULL DEFAULT 0,
    daily_amount_limit_minor BIGINT(20) NOT NULL DEFAULT 0,
    ban_qualified_category TINYINT(4) NOT NULL DEFAULT 0,
    banned_category_codes VARCHAR(1024) DEFAULT NULL,
    enabled TINYINT(4) NOT NULL DEFAULT 1,
    remark VARCHAR(255) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_admission_legal_form UNIQUE (legal_form, tenant_no)
);

CREATE TABLE IF NOT EXISTS mch_deposit
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    merchant_no VARCHAR(64) NOT NULL,
    paid_minor BIGINT(20) NOT NULL DEFAULT 0,
    frozen_minor BIGINT(20) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_mch_deposit_merchant UNIQUE (merchant_no, tenant_no)
);

CREATE TABLE IF NOT EXISTS mch_deposit_txn
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    txn_no VARCHAR(64) NOT NULL,
    merchant_no VARCHAR(64) NOT NULL,
    txn_type VARCHAR(16) NOT NULL,
    amount_minor BIGINT(20) NOT NULL,
    balance_after_minor BIGINT(20) NOT NULL,
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
    CONSTRAINT uk_mch_deposit_txn_no UNIQUE (txn_no, tenant_no)
);

CREATE TABLE IF NOT EXISTS stl_fee_rule
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    rule_no VARCHAR(64) NOT NULL,
    business_mode VARCHAR(24) NOT NULL,
    traffic_source VARCHAR(24) NOT NULL,
    rate_bp INT(11) NOT NULL DEFAULT 0,
    effective_from BIGINT(20) NOT NULL,
    enabled TINYINT(4) NOT NULL DEFAULT 1,
    remark VARCHAR(255) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_fee_rule_no UNIQUE (rule_no, tenant_no),
    CONSTRAINT uk_fee_rule_slot UNIQUE (business_mode, traffic_source, effective_from, tenant_no)
);

CREATE TABLE IF NOT EXISTS sys_region
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    region_code VARCHAR(12) NOT NULL,
    parent_code VARCHAR(12) DEFAULT NULL,
    level VARCHAR(16) NOT NULL,
    name VARCHAR(64) NOT NULL,
    enabled TINYINT(4) NOT NULL DEFAULT 1,
    sort INT(11) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    source VARCHAR(16) NOT NULL DEFAULT 'OFFICIAL',
    owner_entity_no VARCHAR(64) DEFAULT NULL,
    audit_status VARCHAR(16) NOT NULL DEFAULT 'APPROVED',
    reject_reason VARCHAR(255) DEFAULT NULL,
    lat_e6 INT NULL,
    lng_e6 INT NULL,
    coords_source VARCHAR(16) NULL,
    coords_at DATETIME NULL,
    rural TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_region_code UNIQUE (region_code)
);

CREATE TABLE IF NOT EXISTS mch_service_area
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    level VARCHAR(16) NOT NULL,
    ref_code VARCHAR(64) NOT NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'SELF',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    area_no VARCHAR(64) NOT NULL,
    CONSTRAINT uk_service_area_no UNIQUE (area_no),
    PRIMARY KEY (id),
    CONSTRAINT uk_service_area UNIQUE (entity_no,level,ref_code)
);

CREATE TABLE IF NOT EXISTS cmt_community_apply
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    apply_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    address VARCHAR(255) DEFAULT NULL,
    region_code VARCHAR(32) DEFAULT NULL,
    note VARCHAR(255) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    community_no VARCHAR(64) DEFAULT NULL,
    reason VARCHAR(255) DEFAULT NULL,
    submitted_at BIGINT(20) DEFAULT NULL,
    decided_at BIGINT(20) DEFAULT NULL,
    decided_by VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    kind VARCHAR(16) NOT NULL DEFAULT 'ESTATE',
    origin_code VARCHAR(12) DEFAULT NULL,
    lat_e6 INT DEFAULT NULL,
    lng_e6 INT DEFAULT NULL,
    adcode VARCHAR(12) NULL,
    township VARCHAR(64) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_community_apply_no UNIQUE (apply_no)
);

CREATE TABLE IF NOT EXISTS cnt_post
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    post_no VARCHAR(64) NOT NULL,
    author_type VARCHAR(16) NOT NULL,
    author_name VARCHAR(64) DEFAULT NULL,
    title VARCHAR(128) DEFAULT NULL,
    content TEXT DEFAULT NULL,
    community_no VARCHAR(64) DEFAULT NULL,
    community_name VARCHAR(64) DEFAULT NULL,
    sku_no VARCHAR(64) DEFAULT NULL,
    risk_hits TEXT DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    audit_remark VARCHAR(255) DEFAULT NULL,
    audited_by VARCHAR(64) DEFAULT NULL,
    audited_at BIGINT(20) DEFAULT NULL,
    like_count INT(11) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_cnt_post_no UNIQUE (post_no)
);

CREATE TABLE IF NOT EXISTS cnt_question
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    question_no VARCHAR(64) NOT NULL,
    sku_no VARCHAR(64) DEFAULT NULL,
    sku_title VARCHAR(128) DEFAULT NULL,
    content VARCHAR(500) DEFAULT NULL,
    asked_by VARCHAR(64) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    answer VARCHAR(500) DEFAULT NULL,
    answered_by VARCHAR(64) DEFAULT NULL,
    answered_at BIGINT(20) DEFAULT NULL,
    hide_reason VARCHAR(255) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_cnt_question_no UNIQUE (question_no)
);

CREATE TABLE IF NOT EXISTS cnt_ranking
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    rank_no VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    size INT(11) NOT NULL DEFAULT 10,
    manual_skus TEXT DEFAULT NULL,
    enabled TINYINT(4) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_cnt_ranking_no UNIQUE (rank_no)
);

CREATE TABLE IF NOT EXISTS cnt_material
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    material_no VARCHAR(64) NOT NULL,
    title VARCHAR(128) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    content TEXT DEFAULT NULL,
    scope VARCHAR(16) NOT NULL DEFAULT 'ALL',
    scope_refs TEXT DEFAULT NULL,
    langs TEXT DEFAULT NULL,
    published TINYINT(4) NOT NULL DEFAULT 0,
    downloads INT(11) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_cnt_material_no UNIQUE (material_no)
);

CREATE TABLE IF NOT EXISTS sys_function
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    function_code VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    end_code VARCHAR(8) NOT NULL,
    icon VARCHAR(32) DEFAULT NULL,
    href VARCHAR(128) DEFAULT NULL,
    sort INT(11) NOT NULL DEFAULT 0,
    enabled TINYINT(4) NOT NULL DEFAULT 1,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_function UNIQUE (end_code,function_code)
);

CREATE TABLE IF NOT EXISTS sys_function_point
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    point_code VARCHAR(64) NOT NULL,
    function_code VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    group_name VARCHAR(32) DEFAULT NULL,
    href VARCHAR(128) DEFAULT NULL,
    ui_perm_code VARCHAR(64) DEFAULT NULL,
    perm_code VARCHAR(64) DEFAULT NULL,
    backend_status VARCHAR(16) NOT NULL DEFAULT 'IMPLEMENTED',
    ui_ready TINYINT(4) NOT NULL DEFAULT 1,
    matrix_code VARCHAR(16) DEFAULT NULL,
    point_type VARCHAR(8) NOT NULL DEFAULT 'MENU',
    sort INT(11) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    ui_kind VARCHAR(16) NOT NULL DEFAULT 'MENU',
    PRIMARY KEY (id),
    CONSTRAINT uk_point UNIQUE (point_code)
);

CREATE TABLE IF NOT EXISTS sys_role
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    role_code VARCHAR(32) NOT NULL,
    name VARCHAR(32) NOT NULL,
    end_code VARCHAR(8) NOT NULL,
    builtin TINYINT(4) NOT NULL DEFAULT 1,
    wildcard TINYINT(4) NOT NULL DEFAULT 0,
    entity_no VARCHAR(64) DEFAULT NULL,
    sort INT(11) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_role UNIQUE (end_code,role_code,entity_no)
);

CREATE TABLE IF NOT EXISTS sys_role_point
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    role_code VARCHAR(32) NOT NULL,
    point_code VARCHAR(64) NOT NULL,
    end_code VARCHAR(8) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_role_point UNIQUE (role_code,point_code,entity_no)
);

CREATE TABLE IF NOT EXISTS sys_role_member
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    end_code VARCHAR(8) NOT NULL,
    subject_no VARCHAR(64) NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    scope_no VARCHAR(64) DEFAULT NULL,
    granted_by VARCHAR(64) DEFAULT NULL,
    granted_at BIGINT(20) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_role_member UNIQUE (end_code,subject_no,role_code,scope_no)
);

CREATE TABLE IF NOT EXISTS stl_recon_diff
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    diff_no VARCHAR(64) NOT NULL,
    bill_date VARCHAR(10) NOT NULL,
    pay_channel VARCHAR(16) NOT NULL,
    diff_type VARCHAR(24) NOT NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'SELF_CHECK',
    payment_no VARCHAR(64) DEFAULT NULL,
    order_no VARCHAR(64) DEFAULT NULL,
    channel_txn_no VARCHAR(64) DEFAULT NULL,
    channel_amount_minor BIGINT(20) NOT NULL DEFAULT 0,
    platform_amount_minor BIGINT(20) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    resolution VARCHAR(255) DEFAULT NULL,
    recovered_order_no VARCHAR(64) DEFAULT NULL,
    resolved_at BIGINT(20) DEFAULT NULL,
    resolved_by VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_recon_diff_no UNIQUE (diff_no),
    CONSTRAINT uk_recon_diff_payment UNIQUE (bill_date,pay_channel,payment_no,diff_type)
);

CREATE TABLE IF NOT EXISTS mch_staff_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    log_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    actor_account_no VARCHAR(64) DEFAULT NULL,
    target_account_no VARCHAR(64) DEFAULT NULL,
    action VARCHAR(24) NOT NULL,
    store_no VARCHAR(64) DEFAULT NULL,
    role VARCHAR(32) DEFAULT NULL,
    detail VARCHAR(512) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_staff_log_no UNIQUE (log_no)
);

CREATE TABLE IF NOT EXISTS mch_role
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    name VARCHAR(32) NOT NULL,
    perms TEXT NOT NULL,
    builtin TINYINT(1) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_mch_role UNIQUE (entity_no, role_code)
);

CREATE TABLE IF NOT EXISTS sys_notify_log
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    notify_no       VARCHAR(32)  NOT NULL,
    channel         VARCHAR(16)  NOT NULL,
    biz_type        VARCHAR(32)  NOT NULL,
    target          VARCHAR(64)  NOT NULL,
    template_code   VARCHAR(64),
    status          VARCHAR(16)  NOT NULL,
    error           VARCHAR(512),
    provider_msg_id VARCHAR(64),
    operator_no     VARCHAR(32),
    client_ip       VARCHAR(64),
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    template_no VARCHAR(64) DEFAULT NULL,
    provider VARCHAR(16) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_notify_no UNIQUE (notify_no)
);

CREATE TABLE IF NOT EXISTS shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

CREATE TABLE IF NOT EXISTS ord_invoice_request
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    request_no VARCHAR(32) NOT NULL,
    order_no VARCHAR(32) NOT NULL,
    user_no VARCHAR(32) NOT NULL,
    title_type VARCHAR(16) NOT NULL DEFAULT 'PERSONAL',
    title VARCHAR(128) NOT NULL,
    tax_no VARCHAR(32) DEFAULT NULL,
    email VARCHAR(128) NOT NULL,
    amount_minor BIGINT(20) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'REQUESTED',
    invoice_no VARCHAR(64) DEFAULT NULL,
    issued_at BIGINT(20) DEFAULT NULL,
    reject_reason VARCHAR(255) DEFAULT NULL,
    operator_no VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_ord_invoice_request UNIQUE (request_no),
    CONSTRAINT uk_ord_invoice_request_order UNIQUE (order_no, deleted)
);

CREATE TABLE IF NOT EXISTS sys_job_run
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    job_name             VARCHAR(64)  NOT NULL,
    last_run_at          DATETIME     NOT NULL,
    duration_ms          BIGINT       NOT NULL DEFAULT 0,
    status               VARCHAR(16)  NOT NULL,
    detail               VARCHAR(255),
    error                VARCHAR(512),
    consecutive_failures INT          NOT NULL DEFAULT 0,
    run_count            BIGINT       NOT NULL DEFAULT 0,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_job_name UNIQUE (job_name)
);

CREATE TABLE IF NOT EXISTS notify_push_token
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    receiver_type VARCHAR(16) NOT NULL,
    receiver_no VARCHAR(64) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    client_id VARCHAR(128) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    provider VARCHAR(16) NOT NULL DEFAULT 'GETUI',
    CONSTRAINT uk_push_token_receiver UNIQUE (receiver_type, receiver_no, platform, provider),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS stl_withdraw
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    withdraw_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    merchant_name VARCHAR(128) DEFAULT NULL,
    amount_minor BIGINT(20) NOT NULL,
    available_balance_minor BIGINT(20) NOT NULL DEFAULT 0,
    bank_account_masked VARCHAR(64) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    applied_at BIGINT(20) NOT NULL,
    decided_at BIGINT(20) DEFAULT NULL,
    decided_by VARCHAR(64) DEFAULT NULL,
    remark VARCHAR(255) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_stl_withdraw UNIQUE (withdraw_no)
);

CREATE TABLE IF NOT EXISTS stl_settle_invoice
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    invoice_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    merchant_name VARCHAR(128) DEFAULT NULL,
    period VARCHAR(16) NOT NULL,
    amount_minor BIGINT(20) NOT NULL,
    settled_amount_minor BIGINT(20) NOT NULL DEFAULT 0,
    title_type VARCHAR(16) NOT NULL DEFAULT 'COMPANY',
    title VARCHAR(128) NOT NULL,
    tax_no VARCHAR(32) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    serial_no VARCHAR(64) DEFAULT NULL,
    applied_at BIGINT(20) NOT NULL,
    decided_at BIGINT(20) DEFAULT NULL,
    decided_by VARCHAR(64) DEFAULT NULL,
    remark VARCHAR(255) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_stl_settle_invoice UNIQUE (invoice_no),
    CONSTRAINT uk_stl_settle_invoice_period UNIQUE (entity_no, period, deleted)
);

CREATE TABLE IF NOT EXISTS risk_event
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    risk_event_no VARCHAR(64)  NOT NULL,
    type          VARCHAR(32)  NOT NULL,
    subject_type  VARCHAR(16)  NOT NULL,
    subject       VARCHAR(64)  NOT NULL,
    subject_name  VARCHAR(128),
    signals       VARCHAR(512) NOT NULL DEFAULT '',
    refs          VARCHAR(1024) NOT NULL DEFAULT '',
    hit_count     INT          NOT NULL DEFAULT 1,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    verdict       VARCHAR(512),
    decided_by    VARCHAR(64),
    decided_at    DATETIME,
    dedup_key     VARCHAR(160) NOT NULL,
    tenant_no     VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME     NOT NULL,
    created_by    VARCHAR(64)           DEFAULT NULL,
    updated_at    DATETIME     NOT NULL,
    updated_by    VARCHAR(64)           DEFAULT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_risk_event_no UNIQUE (risk_event_no),
    CONSTRAINT uk_risk_event_dedup UNIQUE (dedup_key)
);

CREATE TABLE IF NOT EXISTS risk_signal_hit
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    type         VARCHAR(32)  NOT NULL,
    subject_type VARCHAR(16)  NOT NULL,
    subject      VARCHAR(64)  NOT NULL,
    evidence_ref VARCHAR(64)  NOT NULL,
    detail       VARCHAR(255),
    hit_at       BIGINT       NOT NULL,
    tenant_no    VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME     NOT NULL,
    created_by   VARCHAR(64)           DEFAULT NULL,
    updated_at   DATETIME     NOT NULL,
    updated_by   VARCHAR(64)           DEFAULT NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_risk_hit_ref UNIQUE (type, evidence_ref)
);

CREATE TABLE IF NOT EXISTS risk_blacklist
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    black_no       VARCHAR(64)  NOT NULL,
    subject_type   VARCHAR(16)  NOT NULL,
    subject        VARCHAR(64)  NOT NULL,
    subject_name   VARCHAR(128),
    reason         VARCHAR(512) NOT NULL,
    until_at       DATETIME     NOT NULL,
    appeal_status  VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    appeal_reason  VARCHAR(512),
    appeal_verdict VARCHAR(512),
    active         TINYINT      NOT NULL DEFAULT 1,
    tenant_no      VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at     DATETIME     NOT NULL,
    created_by     VARCHAR(64)           DEFAULT NULL,
    updated_at     DATETIME     NOT NULL,
    updated_by     VARCHAR(64)           DEFAULT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_risk_black_no UNIQUE (black_no)
);

CREATE TABLE IF NOT EXISTS risk_rule
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    type         VARCHAR(32) NOT NULL,
    threshold    INT         NOT NULL,
    window_hours INT         NOT NULL DEFAULT 24,
    auto_block   TINYINT     NOT NULL DEFAULT 0,
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    created_by   VARCHAR(64)          DEFAULT NULL,
    updated_at   DATETIME    NOT NULL,
    updated_by   VARCHAR(64)          DEFAULT NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_risk_rule_type UNIQUE (type)
);

CREATE TABLE IF NOT EXISTS mkt_attribution_rule
(
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    rule_key         VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    priority         VARCHAR(64) NOT NULL DEFAULT 'STORE_CODE,INVITER,CHANNEL',
    window_days      INT         NOT NULL DEFAULT 30,
    conflict_policy  VARCHAR(16) NOT NULL DEFAULT 'OVERWRITE',
    new_user_factors VARCHAR(32) NOT NULL DEFAULT 'DEVICE,PHONE',
    tenant_no        VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at       DATETIME    NOT NULL,
    created_by       VARCHAR(64)          DEFAULT NULL,
    updated_at       DATETIME    NOT NULL,
    updated_by       VARCHAR(64)          DEFAULT NULL,
    version          BIGINT      NOT NULL DEFAULT 0,
    deleted          TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_attr_rule_key UNIQUE (rule_key)
);

CREATE TABLE IF NOT EXISTS mkt_fission_campaign
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    fission_no      VARCHAR(64) NOT NULL,
    name            VARCHAR(128) NOT NULL,
    reward_type     VARCHAR(16) NOT NULL DEFAULT 'COUPON',
    coupon_no       VARCHAR(64) NOT NULL,
    inviter_count   INT         NOT NULL DEFAULT 0,
    invitee_count   INT         NOT NULL DEFAULT 0,
    enabled         TINYINT     NOT NULL DEFAULT 0,
    invited_count   INT         NOT NULL DEFAULT 0,
    converted_count INT         NOT NULL DEFAULT 0,
    tenant_no       VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at      DATETIME    NOT NULL,
    created_by      VARCHAR(64)          DEFAULT NULL,
    updated_at      DATETIME    NOT NULL,
    updated_by      VARCHAR(64)          DEFAULT NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    deleted         TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_fission_no UNIQUE (fission_no)
);

CREATE TABLE IF NOT EXISTS mkt_fission_invite
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    fission_no   VARCHAR(64) NOT NULL,
    inviter_no   VARCHAR(64) NOT NULL,
    invitee_no   VARCHAR(64) NOT NULL,
    device_id    VARCHAR(64),
    phone_tail   VARCHAR(8),
    is_new_user  TINYINT     NOT NULL DEFAULT 1,
    rewarded     TINYINT     NOT NULL DEFAULT 0,
    reward_error VARCHAR(255),
    order_no     VARCHAR(64),
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    created_by   VARCHAR(64)          DEFAULT NULL,
    updated_at   DATETIME    NOT NULL,
    updated_by   VARCHAR(64)          DEFAULT NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_fission_invitee UNIQUE (fission_no, invitee_no)
);

CREATE TABLE IF NOT EXISTS ful_shortage_report
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    sub_order_no VARCHAR(64) NOT NULL,
    pickup_no VARCHAR(64) NOT NULL,
    sku_no VARCHAR(64) DEFAULT NULL,
    kind VARCHAR(16) NOT NULL DEFAULT 'SHORTAGE',
    qty INT(11) NOT NULL DEFAULT 1,
    note VARCHAR(255) DEFAULT NULL,
    reporter_no VARCHAR(64) DEFAULT NULL,
    at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS ful_shipment
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    shipment_no VARCHAR(64) NOT NULL,
    sub_order_no VARCHAR(64) NOT NULL,
    carrier VARCHAR(16) NOT NULL,
    waybill_no VARCHAR(64) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'CREATED',
    receiver VARCHAR(64) DEFAULT NULL,
    region VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_shipment_no UNIQUE (shipment_no),
    CONSTRAINT uk_shipment_sub_order UNIQUE (sub_order_no)
);

CREATE TABLE IF NOT EXISTS ful_shipment_trace
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    shipment_no VARCHAR(64) NOT NULL,
    at BIGINT(20) NOT NULL,
    text VARCHAR(255) NOT NULL,
    location VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS ful_freight_template
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    template_no VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    first_weight_gram INT(11) NOT NULL DEFAULT 1000,
    first_fee BIGINT(20) NOT NULL DEFAULT 0,
    add_weight_gram INT(11) NOT NULL DEFAULT 500,
    add_fee BIGINT(20) NOT NULL DEFAULT 0,
    free_threshold BIGINT(20) NOT NULL DEFAULT 0,
    is_default TINYINT(4) NOT NULL DEFAULT 0,
    out_of_range TEXT DEFAULT NULL,
    archived_at BIGINT(20) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_freight_template_no UNIQUE (template_no)
);

CREATE TABLE IF NOT EXISTS ful_carrier
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    carrier VARCHAR(16) NOT NULL,
    name VARCHAR(64) NOT NULL,
    enabled TINYINT(4) NOT NULL DEFAULT 0,
    priority INT(11) NOT NULL DEFAULT 1,
    account_masked VARCHAR(64) DEFAULT NULL,
    api_key_configured TINYINT(4) NOT NULL DEFAULT 0,
    pickup_cutoff VARCHAR(8) NOT NULL DEFAULT '17:00',
    sla_hours INT(11) NOT NULL DEFAULT 48,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_carrier UNIQUE (carrier),
    CONSTRAINT uk_carrier_priority UNIQUE (priority)
);

CREATE TABLE IF NOT EXISTS sys_merchant_plan_def
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    plan_code         VARCHAR(16)  NOT NULL,
    name              VARCHAR(64)  NOT NULL,
    store_quota       INT          NOT NULL DEFAULT 1,
    staff_quota       INT          NOT NULL DEFAULT 0,
    cross_store_stats TINYINT      NOT NULL DEFAULT 0,
    trial_days        INT          NOT NULL DEFAULT 0,
    enabled           TINYINT      NOT NULL DEFAULT 1,
    sort              INT          NOT NULL DEFAULT 0,
    tenant_no         VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at        DATETIME     NOT NULL,
    created_by        VARCHAR(64)           DEFAULT NULL,
    updated_at        DATETIME     NOT NULL,
    updated_by        VARCHAR(64)           DEFAULT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    deleted           TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_plan_code UNIQUE (plan_code)
);

CREATE TABLE IF NOT EXISTS mch_entity_plan
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    entity_no      VARCHAR(64)  NOT NULL,
    plan_code      VARCHAR(16)  NOT NULL DEFAULT 'FREE',
    store_quota    INT          NOT NULL DEFAULT 1,
    staff_quota    INT          NOT NULL DEFAULT 0,
    cross_store_stats TINYINT   NOT NULL DEFAULT 0,
    store_quota_override INT             DEFAULT NULL,
    staff_quota_override INT             DEFAULT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    start_at       BIGINT                DEFAULT NULL,
    expire_at      BIGINT                DEFAULT NULL,
    granted_by     VARCHAR(16)  NOT NULL DEFAULT 'SELF_PAID',
    trial_used     TINYINT      NOT NULL DEFAULT 0,
    downgraded_at  BIGINT                DEFAULT NULL,
    tenant_no      VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at     DATETIME     NOT NULL,
    created_by     VARCHAR(64)           DEFAULT NULL,
    updated_at     DATETIME     NOT NULL,
    updated_by     VARCHAR(64)           DEFAULT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_entity_plan UNIQUE (entity_no)
);

CREATE TABLE IF NOT EXISTS sys_media_asset
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    asset_key          VARCHAR(255) NOT NULL,
    entity_no          VARCHAR(64)  NOT NULL,
    store_no           VARCHAR(64)  NOT NULL,
    biz_type           VARCHAR(16)  NOT NULL,
    bytes              BIGINT       NOT NULL,
    width              INT                   DEFAULT NULL,
    height             INT                   DEFAULT NULL,
    content_type       VARCHAR(64)           DEFAULT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    last_referenced_at DATETIME              DEFAULT NULL,
    last_ref_desc      VARCHAR(128)          DEFAULT NULL,
    marked_at          DATETIME              DEFAULT NULL,
    uploaded_by        VARCHAR(64)           DEFAULT NULL,
    purge_batch_no     VARCHAR(64)           DEFAULT NULL,
    created_at         DATETIME     NOT NULL,
    updated_at         DATETIME     NOT NULL,
    purged_at          DATETIME              DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_asset_key UNIQUE (asset_key)
);

CREATE TABLE IF NOT EXISTS sys_media_purge_batch
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    batch_no      VARCHAR(64)  NOT NULL,
    operator      VARCHAR(64)  NOT NULL,
    operator_name VARCHAR(64)           DEFAULT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'QUEUED',
    total_count   INT          NOT NULL DEFAULT 0,
    total_bytes   BIGINT       NOT NULL DEFAULT 0,
    purged_count  INT          NOT NULL DEFAULT 0,
    failed_count  INT          NOT NULL DEFAULT 0,
    started_at    DATETIME              DEFAULT NULL,
    finished_at   DATETIME              DEFAULT NULL,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_media_batch_no UNIQUE (batch_no)
);

CREATE TABLE IF NOT EXISTS notify_scene_channel
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    scene_code VARCHAR(48) NOT NULL,
    audience VARCHAR(16) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    enabled TINYINT(4) NOT NULL DEFAULT 1,
    push_level VARCHAR(8) NOT NULL DEFAULT 'NORMAL',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_scene_channel UNIQUE (scene_code, audience, channel)
);

CREATE TABLE IF NOT EXISTS notify_channel
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    channel_no VARCHAR(48) NOT NULL,
    channel_type VARCHAR(16) NOT NULL,
    provider VARCHAR(16) NOT NULL,
    scope VARCHAR(16) NOT NULL,
    owner_no VARCHAR(64) NOT NULL DEFAULT '',
    enabled TINYINT(4) NOT NULL DEFAULT 1,
    priority INT(11) NOT NULL DEFAULT 100,
    cred_ref VARCHAR(64) DEFAULT NULL,
    config_json VARCHAR(1024) NOT NULL DEFAULT '{}',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    secret_cipher VARCHAR(2048) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_notify_channel UNIQUE (channel_type, provider, scope, owner_no),
    CONSTRAINT uk_notify_channel_no UNIQUE (channel_no)
);

CREATE TABLE IF NOT EXISTS notify_push_task
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    task_no VARCHAR(48) NOT NULL,
    name VARCHAR(128) NOT NULL,
    audience_type VARCHAR(32) NOT NULL,
    channel VARCHAR(16) NOT NULL DEFAULT 'PUSH',
    title VARCHAR(128) NOT NULL,
    body VARCHAR(512) NOT NULL,
    link VARCHAR(256) DEFAULT NULL,
    scheduled_at DATETIME DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    estimated_count INT(11) NOT NULL DEFAULT 0,
    sent_count INT(11) NOT NULL DEFAULT 0,
    finished_at DATETIME DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_push_task_no UNIQUE (task_no)
);

CREATE TABLE IF NOT EXISTS prd_spu_std
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    std_no        VARCHAR(64)  NOT NULL,
    category_no   VARCHAR(64)  NOT NULL,
    title         VARCHAR(255) NOT NULL,
    title_i18n    TEXT                  DEFAULT NULL,
    subtitle      VARCHAR(255)          DEFAULT NULL,
    cover         VARCHAR(512)          DEFAULT NULL,
    images        TEXT                  DEFAULT NULL,
    spec_groups   TEXT                  DEFAULT NULL,
    keywords      VARCHAR(512)          DEFAULT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    ref_count     INT          NOT NULL DEFAULT 0,
    tenant_no     VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME     NOT NULL,
    created_by    VARCHAR(64)           DEFAULT NULL,
    updated_at    DATETIME     NOT NULL,
    updated_by    VARCHAR(64)           DEFAULT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    barcode VARCHAR(32) DEFAULT NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'OPS',
    PRIMARY KEY (id),
    CONSTRAINT uk_spu_std_no UNIQUE (std_no),
    CONSTRAINT uk_spu_std_barcode UNIQUE (tenant_no, barcode)
);

CREATE TABLE IF NOT EXISTS mch_store_category
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    store_no     VARCHAR(64) NOT NULL,
    entity_no    VARCHAR(64) NOT NULL,
    category_no  VARCHAR(64) NOT NULL,
    display_name VARCHAR(64)          DEFAULT NULL,
    sort         INT         NOT NULL DEFAULT 0,
    enabled      TINYINT     NOT NULL DEFAULT 1,
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    created_by   VARCHAR(64)          DEFAULT NULL,
    updated_at   DATETIME    NOT NULL,
    updated_by   VARCHAR(64)          DEFAULT NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_store_category UNIQUE (store_no, category_no)
);

CREATE TABLE IF NOT EXISTS prd_store_price
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    store_no      VARCHAR(64)  NOT NULL,
    sku_no        VARCHAR(64)  NOT NULL,
    entity_no     VARCHAR(64)  NOT NULL,
    market        VARCHAR(8)   NOT NULL DEFAULT 'CN',
    price         BIGINT       NOT NULL,
    origin_price  BIGINT       DEFAULT NULL,
    tenant_no     VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_by    VARCHAR(64)  DEFAULT NULL,
    updated_by    VARCHAR(64)  DEFAULT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_store_sku_market UNIQUE (store_no, sku_no, market)
);

CREATE TABLE IF NOT EXISTS prd_topic
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    topic_no   VARCHAR(64)  NOT NULL,
    title      VARCHAR(64)  NOT NULL,
    subtitle   VARCHAR(128) DEFAULT NULL,
    cover      VARCHAR(512) DEFAULT NULL,
    sort       INT          NOT NULL DEFAULT 0,
    start_at   DATETIME     DEFAULT NULL,
    end_at     DATETIME     DEFAULT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    tenant_no  VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_by VARCHAR(64)  DEFAULT NULL,
    updated_by VARCHAR(64)  DEFAULT NULL,
    version    BIGINT       NOT NULL DEFAULT 0,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_topic_no UNIQUE (topic_no)
);

CREATE TABLE IF NOT EXISTS prd_topic_goods
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    topic_no   VARCHAR(64) NOT NULL,
    goods_no   VARCHAR(64) NOT NULL,
    entity_no  VARCHAR(64) NOT NULL,
    sort       INT         NOT NULL DEFAULT 0,
    tenant_no  VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_by VARCHAR(64) DEFAULT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted    TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_topic_goods UNIQUE (topic_no, goods_no)
);

CREATE TABLE IF NOT EXISTS mch_fulfillment_channel
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    store_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    channel VARCHAR(24) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 0,
    scope_mode VARCHAR(8) NOT NULL DEFAULT 'ALL',
    config TEXT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    ops_locked TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_store_channel UNIQUE (tenant_no, store_no, channel)
);

CREATE TABLE IF NOT EXISTS mch_channel_pickup
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    store_no VARCHAR(64) NOT NULL,
    channel VARCHAR(24) NOT NULL,
    pickup_no VARCHAR(64) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_channel_pickup UNIQUE (tenant_no, store_no, channel, pickup_no)
);

CREATE TABLE IF NOT EXISTS mch_channel_area
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    store_no VARCHAR(64) NOT NULL,
    channel VARCHAR(24) NOT NULL,
    area_no VARCHAR(64) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_channel_area UNIQUE (tenant_no, store_no, channel, area_no)
);

CREATE TABLE IF NOT EXISTS prd_spec_dim
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    dim_no VARCHAR(64) NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    name_i18n TEXT DEFAULT NULL,
    value_type VARCHAR(16) NOT NULL DEFAULT 'ENUM',
    unit VARCHAR(16) DEFAULT NULL,
    usage_type VARCHAR(16) NOT NULL DEFAULT 'SALE',
    universal TINYINT NOT NULL DEFAULT 0,
    scope VARCHAR(16) NOT NULL DEFAULT 'PLATFORM',
    entity_no VARCHAR(64) DEFAULT NULL,
    sort INT NOT NULL DEFAULT 100,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_spec_dim_no UNIQUE (dim_no),
    CONSTRAINT uk_spec_dim_code UNIQUE (tenant_no, code, scope, entity_no)
);

CREATE TABLE IF NOT EXISTS prd_spec_value
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    value_no VARCHAR(64) NOT NULL,
    dim_no VARCHAR(64) NOT NULL,
    code VARCHAR(32) NOT NULL,
    label VARCHAR(64) NOT NULL,
    label_i18n TEXT DEFAULT NULL,
    numeric_value DECIMAL(14,4) DEFAULT NULL,
    numeric_unit VARCHAR(16) DEFAULT NULL,
    aliases TEXT DEFAULT NULL,
    scope VARCHAR(16) NOT NULL DEFAULT 'PLATFORM',
    entity_no VARCHAR(64) DEFAULT NULL,
    sort INT NOT NULL DEFAULT 100,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    merged_into VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_spec_value_no UNIQUE (value_no),
    CONSTRAINT uk_spec_value_code UNIQUE (tenant_no, dim_no, code, scope, entity_no)
);

CREATE TABLE IF NOT EXISTS prd_category_spec
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    category_no VARCHAR(64) NOT NULL,
    dim_no VARCHAR(64) NOT NULL,
    usage_type VARCHAR(16) DEFAULT NULL,
    is_primary TINYINT NOT NULL DEFAULT 0,
    required TINYINT NOT NULL DEFAULT 0,
    sort INT NOT NULL DEFAULT 100,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_cat_spec UNIQUE (tenant_no, category_no, dim_no)
);

CREATE TABLE IF NOT EXISTS prd_category_spec_value
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    category_no VARCHAR(64) NOT NULL,
    dim_no VARCHAR(64) NOT NULL,
    value_no VARCHAR(64) NOT NULL,
    label_override VARCHAR(64) DEFAULT NULL,
    sort INT NOT NULL DEFAULT 100,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_cat_spec_value UNIQUE (tenant_no, category_no, dim_no, value_no)
);

CREATE TABLE IF NOT EXISTS prd_merchant_spec
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    dim_no VARCHAR(64) NOT NULL,
    sort INT NOT NULL DEFAULT 100,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_mch_spec UNIQUE (tenant_no, entity_no, dim_no)
);

CREATE TABLE IF NOT EXISTS prd_merchant_spec_value
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    dim_no VARCHAR(64) NOT NULL,
    value_no VARCHAR(64) NOT NULL,
    sort INT NOT NULL DEFAULT 100,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_mch_spec_value UNIQUE (tenant_no, entity_no, dim_no, value_no)
);

CREATE TABLE IF NOT EXISTS geo_poi_cache
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    scope_code VARCHAR(64) NOT NULL,
    parent_code VARCHAR(32) NOT NULL,
    kind        VARCHAR(16)  NOT NULL DEFAULT 'ESTATE',
    source      VARCHAR(16)  NOT NULL DEFAULT 'AMAP',
    payload     MEDIUMTEXT   NOT NULL,
    item_count  INT          NOT NULL DEFAULT 0,
    fetched_at  DATETIME     NOT NULL,
    tenant_no   VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(64),
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by  VARCHAR(64),
    version     BIGINT       NOT NULL DEFAULT 0,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_geo_poi_scope UNIQUE (scope_code, kind)
);

CREATE TABLE IF NOT EXISTS prd_merchant_spec_override
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_no VARCHAR(64) NOT NULL,
    category_no VARCHAR(64) NOT NULL,
    dim_no VARCHAR(64) NOT NULL,
    value_no VARCHAR(64) NOT NULL DEFAULT '',
    enabled TINYINT NOT NULL DEFAULT 1,
    sort INT DEFAULT NULL,
    label_override VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_mch_spec_override UNIQUE (tenant_no, merchant_no, category_no, dim_no, value_no)
);

CREATE TABLE IF NOT EXISTS usr_person
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    person_no VARCHAR(64) NOT NULL,
    phone_hash VARCHAR(64) NOT NULL,
    phone_enc VARCHAR(255) DEFAULT NULL,
    phone_tail VARCHAR(8) DEFAULT NULL,
    user_no VARCHAR(64) DEFAULT NULL,
    merged_into VARCHAR(64) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_person_no UNIQUE (person_no),
    CONSTRAINT uk_person_phone UNIQUE (tenant_no, phone_hash),
    CONSTRAINT uk_person_user UNIQUE (tenant_no, user_no)
);

CREATE TABLE IF NOT EXISTS usr_person_merge_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    from_person_no VARCHAR(64) NOT NULL,
    to_person_no VARCHAR(64) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    affected_members INT(11) NOT NULL DEFAULT 0,
    operator_no VARCHAR(64) DEFAULT NULL,
    merged_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS mbr_setting
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    member_scope VARCHAR(16) NOT NULL DEFAULT 'ENTITY',
    auto_join_on_order TINYINT(4) NOT NULL DEFAULT 1,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_mbr_setting_entity UNIQUE (tenant_no, entity_no)
);

CREATE TABLE IF NOT EXISTS mbr_member
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    member_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    person_no VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    source VARCHAR(16) NOT NULL,
    first_store_no VARCHAR(64) DEFAULT NULL,
    first_order_at BIGINT(20) DEFAULT NULL,
    last_order_at BIGINT(20) DEFAULT NULL,
    order_count INT(11) NOT NULL DEFAULT 0,
    total_spent_minor BIGINT(20) NOT NULL DEFAULT 0,
    d90_order_count INT(11) NOT NULL DEFAULT 0,
    d90_spent_minor BIGINT(20) NOT NULL DEFAULT 0,
    level VARCHAR(16) DEFAULT NULL,
    reach_opt_out TINYINT(4) NOT NULL DEFAULT 0,
    remark VARCHAR(255) DEFAULT NULL,
    joined_at BIGINT(20) NOT NULL,
    claimed_at BIGINT(20) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_mbr_member_no UNIQUE (member_no),
    CONSTRAINT uk_mbr_member_person UNIQUE (tenant_no, entity_no, person_no)
);

CREATE TABLE IF NOT EXISTS mbr_member_store
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    member_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    first_order_at BIGINT(20) DEFAULT NULL,
    last_order_at BIGINT(20) DEFAULT NULL,
    order_count INT(11) NOT NULL DEFAULT 0,
    total_spent_minor BIGINT(20) NOT NULL DEFAULT 0,
    d90_order_count INT(11) NOT NULL DEFAULT 0,
    d90_spent_minor BIGINT(20) NOT NULL DEFAULT 0,
    level VARCHAR(16) DEFAULT NULL,
    is_first_store TINYINT(4) NOT NULL DEFAULT 0,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_mbr_member_store UNIQUE (tenant_no, member_no, store_no)
);

CREATE TABLE IF NOT EXISTS mbr_member_source
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    source_no VARCHAR(64) NOT NULL,
    member_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    store_no VARCHAR(64) DEFAULT NULL,
    link_no VARCHAR(64) DEFAULT NULL,
    ref_no VARCHAR(64) DEFAULT NULL,
    inviter_user_no VARCHAR(64) DEFAULT NULL,
    inviter_role VARCHAR(16) DEFAULT NULL,
    operator_no VARCHAR(64) DEFAULT NULL,
    activity_no VARCHAR(64) DEFAULT NULL,
    is_first TINYINT(4) NOT NULL DEFAULT 0,
    occurred_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_mbr_source_no UNIQUE (source_no)
);

CREATE TABLE IF NOT EXISTS mbr_tag
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    tag_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    name VARCHAR(32) NOT NULL,
    tag_type VARCHAR(8) NOT NULL DEFAULT 'MCH',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    merged_into VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_mbr_tag_no UNIQUE (tag_no),
    CONSTRAINT uk_mbr_tag_name UNIQUE (tenant_no, entity_no, name)
);

CREATE TABLE IF NOT EXISTS mbr_member_tag
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    member_no VARCHAR(64) NOT NULL,
    tag_no VARCHAR(64) NOT NULL,
    tag_type VARCHAR(8) NOT NULL,
    tagged_by VARCHAR(64) DEFAULT NULL,
    tagged_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_mbr_member_tag UNIQUE (tenant_no, member_no, tag_no)
);

CREATE TABLE IF NOT EXISTS mbr_tag_merge_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    from_tag_no VARCHAR(64) NOT NULL,
    to_tag_no VARCHAR(64) NOT NULL,
    affected_count INT(11) NOT NULL DEFAULT 0,
    operator_no VARCHAR(64) DEFAULT NULL,
    merged_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS mbr_segment
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    segment_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    scope_store_no VARCHAR(64) DEFAULT NULL,
    rule_json TEXT NOT NULL,
    last_count INT(11) NOT NULL DEFAULT 0,
    counted_at BIGINT(20) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_mbr_segment_no UNIQUE (segment_no),
    CONSTRAINT uk_mbr_segment_name UNIQUE (tenant_no, entity_no, name)
);

CREATE TABLE IF NOT EXISTS pmt_coupon
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    coupon_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL,
    funder VARCHAR(16) NOT NULL DEFAULT 'MERCHANT',
    title VARCHAR(128) NOT NULL,
    benefit_mode VARCHAR(16) NOT NULL DEFAULT 'CASH',
    benefit_value BIGINT(20) NOT NULL DEFAULT 0,
    benefit_cap_minor BIGINT(20) DEFAULT NULL,
    benefit_ref VARCHAR(64) DEFAULT NULL,
    min_amount_minor BIGINT(20) DEFAULT NULL,
    min_qty INT(11) DEFAULT NULL,
    scope_type VARCHAR(16) NOT NULL DEFAULT 'ALL',
    scope_desc VARCHAR(128) DEFAULT NULL,
    validity_mode VARCHAR(16) NOT NULL DEFAULT 'ABSOLUTE',
    start_at BIGINT(20) DEFAULT NULL,
    end_at BIGINT(20) DEFAULT NULL,
    valid_days INT(11) DEFAULT NULL,
    issue_mode VARCHAR(16) NOT NULL DEFAULT 'CENTER',
    redeem_mode VARCHAR(16) NOT NULL DEFAULT 'ORDER',
    times_total INT(11) NOT NULL DEFAULT 1,
    total_count INT(11) DEFAULT NULL,
    received_count INT(11) NOT NULL DEFAULT 0,
    per_user_limit INT(11) NOT NULL DEFAULT 1,
    budget_minor BIGINT(20) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    archived_at DATETIME DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_pmt_coupon_no UNIQUE (coupon_no)
);

CREATE TABLE IF NOT EXISTS pmt_coupon_scope
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    coupon_no VARCHAR(64) NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    ref_no VARCHAR(64) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_pmt_coupon_scope UNIQUE (tenant_no, coupon_no, scope_type, ref_no)
);

CREATE TABLE IF NOT EXISTS pmt_user_coupon
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_coupon_no VARCHAR(64) NOT NULL,
    coupon_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL,
    issue_no VARCHAR(64) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UNUSED',
    times_used INT(11) NOT NULL DEFAULT 0,
    order_no VARCHAR(64) DEFAULT NULL,
    used_at BIGINT(20) DEFAULT NULL,
    received_at BIGINT(20) NOT NULL,
    expire_at BIGINT(20) NOT NULL,
    redeem_code VARCHAR(32) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_pmt_user_coupon_no UNIQUE (user_coupon_no),
    CONSTRAINT uk_pmt_redeem_code UNIQUE (tenant_no, redeem_code)
);

CREATE TABLE IF NOT EXISTS pmt_coupon_issue
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    issue_no VARCHAR(64) NOT NULL,
    coupon_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL,
    issue_mode VARCHAR(16) NOT NULL,
    segment_no VARCHAR(64) DEFAULT NULL,
    activity_no VARCHAR(64) DEFAULT NULL,
    rule_snapshot TEXT DEFAULT NULL,
    planned_count INT(11) NOT NULL DEFAULT 0,
    issued_count INT(11) NOT NULL DEFAULT 0,
    skipped_count INT(11) NOT NULL DEFAULT 0,
    skip_detail VARCHAR(255) DEFAULT NULL,
    amount_minor BIGINT(20) NOT NULL DEFAULT 0,
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
    CONSTRAINT uk_pmt_issue_no UNIQUE (issue_no)
);

CREATE TABLE IF NOT EXISTS pmt_apply
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    apply_no VARCHAR(64) NOT NULL,
    promo_type VARCHAR(16) NOT NULL,
    promo_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL,
    store_no VARCHAR(64) DEFAULT NULL,
    order_no VARCHAR(64) DEFAULT NULL,
    sub_order_no VARCHAR(64) DEFAULT NULL,
    redeem_mode VARCHAR(16) NOT NULL DEFAULT 'ORDER',
    operator_no VARCHAR(64) DEFAULT NULL,
    amount_minor BIGINT(20) NOT NULL DEFAULT 0,
    funder VARCHAR(16) NOT NULL DEFAULT 'MERCHANT',
    applied_at BIGINT(20) NOT NULL,
    reverted_at BIGINT(20) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_pmt_apply_no UNIQUE (apply_no)
);

-- 种子数据
INSERT INTO sys_industry VALUES
(1,'CATERING','餐饮',10,1,1,0,0,'微信小微白名单内','MAIN','2026-08-09 12:49:36','SYSTEM','2026-08-09 12:49:36',NULL,0,0),
(2,'RETAIL','线下零售',20,1,1,0,0,'微信小微白名单内。便利店、超市、生鲜果蔬都归这一类','MAIN','2026-08-09 12:49:36','SYSTEM','2026-08-09 12:49:36',NULL,0,0),
(3,'LIFE_SERVICE','居民生活服务',30,1,1,0,0,'微信小微白名单内。家政、维修、洗衣等','MAIN','2026-08-09 12:49:36','SYSTEM','2026-08-09 12:49:36',NULL,0,0),
(4,'ENTERTAINMENT','休闲娱乐',40,1,1,0,0,'微信小微白名单内','MAIN','2026-08-09 12:49:36','SYSTEM','2026-08-09 12:49:36',NULL,0,0),
(5,'TRANSPORT','交通出行',50,1,1,0,0,'微信小微白名单内','MAIN','2026-08-09 12:49:36','SYSTEM','2026-08-09 12:49:36',NULL,0,0),
(6,'ONLINE','线上/虚拟',60,1,0,0,0,'微信**明确不支持**小微：直播、游戏等线上业态','MAIN','2026-08-09 12:49:36','SYSTEM','2026-08-09 12:49:36',NULL,0,0),
(7,'OTHER','其他',99,1,0,0,0,'保守兜底：没归到上面任何一类时选它，**不可小微** —— 宁可让商家来问，也不要让他被通道拒','MAIN','2026-08-09 12:49:36','SYSTEM','2026-08-09 12:49:36',NULL,0,0);
INSERT INTO sys_legal_form VALUES
(1,'MICRO','小微商户',10,1,'PERSONAL','MICRO',NULL,0,'PERSONAL_OPENID',1,'免营业执照，钱打到个人。受行业白名单限制 —— 线上业态不收。支付宝侧尚未确认，故 alipay_code 留空','MAIN','2026-08-09 12:49:36','SYSTEM','2026-08-09 12:49:36',NULL,0,0),
(2,'INDIVIDUAL','个体工商户',20,1,'INDIVIDUAL_BIZ','SMALL','INDIVIDUAL',1,'MERCHANT_ID',0,'需营业执照，不受行业白名单限制','MAIN','2026-08-09 12:49:36','SYSTEM','2026-08-09 12:49:36',NULL,0,0),
(3,'ENTERPRISE','企业',30,1,'COMPANY','ENTERPRISE','ENTERPRISE',1,'MERCHANT_ID',0,'需营业执照与对公账户','MAIN','2026-08-09 12:49:36','SYSTEM','2026-08-09 12:49:36',NULL,0,0);
INSERT INTO sys_pay_channel VALUES
(1,'WECHAT','微信支付',1,1,1,1,'[\"JSAPI\",\"APP\",\"H5\",\"NATIVE\"]','[\"CN\"]',NULL,'MAIN','2026-08-09 12:49:35','SYSTEM','2026-08-09 12:49:35',NULL,0,0,50,60,10000),
(2,'ALIPAY','支付宝',1,1,1,1,'[\"JSAPI\",\"APP\",\"H5\"]','[\"CN\"]',NULL,'MAIN','2026-08-09 12:49:35','SYSTEM','2026-08-09 12:49:35',NULL,0,0,0,0,3000);
INSERT INTO sys_channel_category_rule VALUES
(1,'MP_WECHAT','GOODS',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(2,'MP_WECHAT','FRESH',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(3,'MP_WECHAT','SERVICE',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(4,'MP_WECHAT','VIRTUAL',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(5,'MP_WECHAT','CARD',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(6,'MP_ALIPAY','GOODS',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(7,'MP_ALIPAY','FRESH',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(8,'MP_ALIPAY','SERVICE',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(9,'MP_ALIPAY','VIRTUAL',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(10,'MP_ALIPAY','CARD',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(11,'ANDROID','GOODS',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(12,'ANDROID','FRESH',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(13,'ANDROID','SERVICE',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(14,'ANDROID','VIRTUAL',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(15,'ANDROID','CARD',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(16,'IOS','GOODS',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(17,'IOS','FRESH',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(18,'IOS','SERVICE',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(19,'IOS','VIRTUAL',0,'iOS 平台规则限制，请在小程序端购买',NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(20,'IOS','CARD',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(21,'H5','GOODS',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(22,'H5','FRESH',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(23,'H5','SERVICE',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(24,'H5','VIRTUAL',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0),
(25,'H5','CARD',1,NULL,NULL,'MAIN','2026-08-09 12:49:31','SYSTEM','2026-08-09 12:49:31',0,0);
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES

('CAT100', NULL,     1, '食品生鲜', 'Fresh Food', NULL, 10, 'FRESH',    NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT200', NULL,     1, '日用百货', 'Household',  NULL, 20, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT300', NULL,     1, '生活服务', 'Services',   NULL, 30, 'SERVICE',  NULL, '["家电维修资质"]', 'SERVICE_REPAIR', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT400', NULL,     1, '卡券',     'Vouchers',   NULL, 40, 'VOUCHER',  NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),

('CAT110', 'CAT100', 2, '蔬菜', 'Vegetables',     NULL, 10, 'FRESH',    NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT120', 'CAT100', 2, '水果', 'Fruits',         NULL, 20, 'FRESH',    NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT210', 'CAT200', 2, '纸品清洁', 'Cleaning',   NULL, 10, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),

('CAT111', 'CAT110', 3, '叶菜',   'Leafy Greens', NULL, 10, 'FRESH', NULL, '["食品经营许可证"]', 'FRESH_VEG',   'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT112', 'CAT110', 3, '根茎菜', 'Root Veg',     NULL, 20, 'FRESH', NULL, '["食品经营许可证"]', 'FRESH_VEG',   'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT121', 'CAT120', 3, '浆果',   'Berries',      NULL, 10, 'FRESH', NULL, '["食品经营许可证"]', 'FRESH_FRUIT', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO sys_auth_code
(code, name, required_qualification, sort, enabled, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('FRESH_VEG',      '蔬菜',     '食品经营许可证', 10, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('FRESH_FRUIT',    '水果',     '食品经营许可证', 20, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('FRESH_DAIRY',    '乳制品',   '食品经营许可证', 30, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('FOOD',           '熟食加工', '食品经营许可证', 40, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('DAILY',          '日用百货', NULL,             50, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SERVICE_REPAIR', '维修服务', '家电维修资质',   60, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
UPDATE prd_goods SET category_no = 'CAT210' WHERE category_no IN ('CAT001', 'CAT002');
UPDATE prd_goods SET category_no = 'CAT300' WHERE category_no = 'CAT003';
DELETE FROM prd_category
WHERE category_no IN ('CAT-ROOT-1', 'CAT-ROOT-2', 'CAT001', 'CAT002', 'CAT003');
INSERT INTO sys_setting
(setting_key, setting_value, remark, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('review.score-config',
 '{"weightProduct":50,"weightFulfill":30,"weightService":20,"newMerchantProtectDays":30,"decayHalfLifeDays":180}',
 '评价三维权重与保护期。改它会改变历史评价的呈现（时效衰减是实时算的）',
 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO sys_setting
(setting_key, setting_value, remark, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('store.sensitive-words',
 '["最低价","全网第一","国家级","绝对","包治","微信","加V","私聊"]',
 '店招与公告的机审词表。命中即转人审（不是直接拒）—— 词表总会误伤，人审是纠偏的那一层',
 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
UPDATE sys_channel_category_rule SET category_type = 'NORMAL' WHERE category_type = 'GOODS';
INSERT INTO sys_auth_code
(code, name, required_qualification, sort, enabled, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('PACKAGED_FOOD', '预包装食品', '仅销售预包装食品备案', 25, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),


('HOUSEKEEPING',  '家政服务', NULL,                     65, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES

('CAT122', 'CAT120', 3, '常温水果', 'Fruits (Ambient)', NULL, 20, 'FRESH', NULL,
 '["营业执照（食用农产品）"]', 'FRESH_FRUIT', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),


('CAT130', 'CAT100', 2, '预包装食品', 'Packaged Food', NULL, 30, 'STANDARD', NULL,
 NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT131', 'CAT130', 3, '粮油调味', 'Grain & Oil',    NULL, 10, 'STANDARD', NULL,
 '["仅销售预包装食品备案"]', 'PACKAGED_FOOD', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT132', 'CAT130', 3, '休闲零食', 'Snacks',         NULL, 20, 'STANDARD', NULL,
 '["仅销售预包装食品备案"]', 'PACKAGED_FOOD', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT133', 'CAT130', 3, '茶叶',     'Tea',            NULL, 30, 'STANDARD', NULL,
 '["仅销售预包装食品备案"]', 'PACKAGED_FOOD', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),


('CAT220', 'CAT200', 2, '家居用品', 'Home',      NULL, 20, 'STANDARD', NULL,
 NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT230', 'CAT200', 2, '个护化妆', 'Personal Care', NULL, 30, 'STANDARD', NULL,
 NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),




('CAT310', 'CAT300', 2, '家政保洁', 'Housekeeping', NULL, 10, 'SERVICE', NULL,
 NULL, 'HOUSEKEEPING', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
UPDATE prd_goods SET category_no = 'CAT310' WHERE category_no = 'CAT300';
UPDATE prd_category
SET required_code = NULL, qualification_required = NULL
WHERE category_no = 'CAT300';
UPDATE prd_category SET status = 'ARCHIVED' WHERE category_no = 'CAT400';
UPDATE sys_auth_code SET required_qualification = '营业执照（食用农产品）'
WHERE code IN ('FRESH_VEG', 'FRESH_FRUIT');
UPDATE prd_category SET qualification_required = '["营业执照（食用农产品）"]'
WHERE required_code IN ('FRESH_VEG', 'FRESH_FRUIT');
UPDATE sys_auth_code SET enabled = 0 WHERE code IN ('FRESH_DAIRY', 'FOOD', 'SERVICE_REPAIR');
UPDATE sys_industry SET enabled = 0,
    remark = '一期停用：平台执照无餐饮服务与热食制售（自营模式下平台是销售者）。拿到相应许可后可放开'
WHERE industry = 'CATERING';
UPDATE sys_industry SET enabled = 0,
    remark = '一期停用：平台执照无相关经营项'
WHERE industry IN ('ENTERTAINMENT', 'TRANSPORT');
UPDATE sys_industry SET enabled = 0,
    remark = '一期停用：不上虚拟商品与卡券（iOS 小程序虚拟支付受限）'
WHERE industry = 'ONLINE';
UPDATE sys_industry SET enabled = 0,
    remark = '一期停用：自营模式下「其他」等于平台不清楚自己在销售什么，无法对应执照经营范围'
WHERE industry = 'OTHER';
UPDATE sys_industry SET
    remark = '一期启用：执照含日用品、水果、蔬菜、预包装食品、茶叶 批发与零售'
WHERE industry = 'RETAIL';
UPDATE sys_industry SET
    remark = '一期启用，但仅家政：执照含「家政服务」，无维修/洗衣。细分品类由类目树限制'
WHERE industry = 'LIFE_SERVICE';
INSERT INTO sys_setting
(setting_key, setting_value, remark, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('merchant.service-scope-enabled', '["COMMUNITY","CITY"]',
 '一期自营模式：PLATFORM 档没有商品形态支撑（无虚拟商品、无卡券、无平台自营快递品）。切平台模式后放开',
 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
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
INSERT INTO stl_fee_rule
    (rule_no, business_mode, traffic_source, rate_bp, effective_from, enabled, remark,
     tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    ('FR-INIT-TP-OWNED', 'THIRD_PARTY', 'MERCHANT_OWNED', 0, 0, 1,
     '自带客流零佣金：他带来的客户在别家消费才是平台收益（R16）',
     'MAIN', NOW(), 'SYSTEM', NOW(), NULL, 0, 0),
    ('FR-INIT-TP-PLAT', 'THIRD_PARTY', 'PLATFORM', 500, 0, 1,
     '平台客流 5%，沿用上线前 shop.settle.platform-rate 的默认值',
     'MAIN', NOW(), 'SYSTEM', NOW(), NULL, 0, 0),
    ('FR-INIT-SO-OWNED', 'SELF_OPERATED', 'MERCHANT_OWNED', 0, 0, 1,
     '自营·自带客流：先与第三方取齐，等自营有量后再单独定',
     'MAIN', NOW(), 'SYSTEM', NOW(), NULL, 0, 0),
    ('FR-INIT-SO-PLAT', 'SELF_OPERATED', 'PLATFORM', 500, 0, 1,
     '自营·平台客流：先与第三方取齐，等自营有量后再单独定',
     'MAIN', NOW(), 'SYSTEM', NOW(), NULL, 0, 0);
UPDATE prd_goods
SET fulfillments = '["STORE_PICKUP","NEIGHBOR_PICKUP","MERCHANT_DELIVERY","EXPRESS"]'
WHERE fulfillments IS NULL
   OR fulfillments = ''
   OR fulfillments = '["STORE_PICKUP"]';
UPDATE mch_entity SET fulfillment_reach = CASE service_scope
    WHEN 'CITY' THEN 'ONSITE'
    WHEN 'PLATFORM' THEN 'SHIPPING'
    ELSE 'PICKUP'
END;
UPDATE mch_service_area SET area_no = CONCAT('SVA', LPAD(id, 12, '0')) WHERE area_no IS NULL;
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_DASHBOARD', '经营看板', 'OPS', 'LayoutDashboard', '/', 10, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_MERCHANT', '商家治理', 'OPS', 'Store', '/merchants', 20, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT_01', 'OPS_MERCHANT', '入驻审核', '入驻与资质', '/merchants', 'merchant:apply:audit', 'merchant:audit', 'IMPLEMENTED', 1, 'P-11.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT_02', 'OPS_MERCHANT', '商家档案', '入驻与资质', '/merchants?tab=list', 'merchant:merchant:read', 'merchant:audit', 'IMPLEMENTED', 1, 'P-11.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT_03', 'OPS_MERCHANT', '类目授权', '入驻与资质', '/merchants?tab=categories', 'merchant:category:grant', 'merchant:audit', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT_04', 'OPS_MERCHANT', '认证标管理', '信用与处置', '/merchants?tab=verify', 'merchant:verify:grant', 'merchant:audit', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT_05', 'OPS_MERCHANT', '信用档案', '信用与处置', '/merchants?tab=credit', 'merchant:merchant:read', 'merchant:audit', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT_06', 'OPS_MERCHANT', '违规处置与封禁', '信用与处置', '/merchants?tab=ban', 'merchant:merchant:ban', 'merchant:audit', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_STORE', '门店主页', 'OPS', 'LayoutTemplate', '/stores', 30, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_STORE_01', 'OPS_STORE', '店招公告审核', '模板与合规', '/stores', 'store:page:audit', NULL, 'NOT_IMPLEMENTED', 1, 'P-10.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_STORE_02', 'OPS_STORE', '主页模板配置', '模板与合规', '/stores?tab=template', 'store:page:read', NULL, 'NOT_IMPLEMENTED', 0, 'P-10.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_STORE_03', 'OPS_STORE', '店铺码生成导出', '获客', '/stores?tab=qrcode', 'store:qrcode:export', NULL, 'NOT_IMPLEMENTED', 1, 'P-10.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_STORE_04', 'OPS_STORE', '获客效果看板', '获客', '/stores?tab=effect', 'store:page:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-10.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_PRODUCT', '商品与类目', 'OPS', 'Package', '/products', 40, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_PRODUCT_01', 'OPS_PRODUCT', '三级类目树', '类目', '/products', 'product:category:read', 'category:manage', 'IMPLEMENTED', 1, 'P-3.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_PRODUCT_02', 'OPS_PRODUCT', '商品池与审核', '商品', '/products?tab=skus', 'product:sku:read', 'goods:audit', 'IMPLEMENTED', 1, 'P-3.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_PRODUCT_03', 'OPS_PRODUCT', '预售额度与超卖', '库存与预售', '/products?tab=stock', 'product:stock:update', 'goods:audit', 'IMPLEMENTED', 1, 'P-3.3', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_ORDER', '交易订单', 'OPS', 'ReceiptText', '/orders', 50, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER_01', 'OPS_ORDER', '订单检索', '订单', '/orders', 'order:order:read', 'order:view', 'IMPLEMENTED', 1, 'P-4.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER_02', 'OPS_ORDER', '异常单处理', '订单', '/orders?tab=exception', 'order:order:modify', 'order:intervene', 'IMPLEMENTED', 0, 'P-4.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER_03', 'OPS_ORDER', '代客下单/取消', '订单', '/orders?tab=proxy', 'order:order:proxy', 'order:intervene', 'IMPLEMENTED', 0, 'P-4.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER_04', 'OPS_ORDER', '支付流水核对', '支付', '/orders?tab=pay', 'order:pay:read', 'order:view', 'IMPLEMENTED', 0, 'P-4.2', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER_05', 'OPS_ORDER', '掉单补偿', '支付', '/orders?tab=repair', 'order:pay:repair', 'order:intervene', 'IMPLEMENTED', 0, 'P-4.2', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER_06', 'OPS_ORDER', '关单策略配置', '支付', '/orders?tab=close', 'order:pay:repair', 'order:intervene', 'IMPLEMENTED', 0, 'P-4.2', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_FULFILLMENT', '履约调度', 'OPS', 'Truck', '/fulfillment', 60, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT_01', 'OPS_FULFILLMENT', '到货批次与配车', '到货与分拣', '/fulfillment', 'fulfillment:batch:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT_02', 'OPS_FULFILLMENT', '按自提点汇总分拣', '到货与分拣', '/fulfillment?tab=sorting', 'fulfillment:batch:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT_03', 'OPS_FULFILLMENT', '核销监控与逾期', '核销', '/fulfillment?tab=redeem', 'fulfillment:redeem:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT_04', 'OPS_FULFILLMENT', '逾期规则配置', '核销', '/fulfillment?tab=overdue', 'fulfillment:rule:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT_05', 'OPS_FULFILLMENT', '快递与轨迹', '物流', '/fulfillment?tab=express', 'fulfillment:logistics:read', NULL, 'NOT_IMPLEMENTED', 0, 'P-5.2', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT_06', 'OPS_FULFILLMENT', '运费模板与超区', '物流', '/fulfillment?tab=freight', 'fulfillment:rule:update', NULL, 'NOT_IMPLEMENTED', 0, 'P-5.2', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT_07', 'OPS_FULFILLMENT', '第三方运力配置', '物流', '/fulfillment?tab=carrier', 'fulfillment:logistics:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.2', 'MENU', 70, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_AFTERSALE', '售后治理', 'OPS', 'Undo2', '/after-sales', 70, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_AFTERSALE_01', 'OPS_AFTERSALE', '售后工单池', '处置', '/after-sales', 'aftersale:ticket:read', 'ticket:handle', 'IMPLEMENTED', 1, 'P-6.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_AFTERSALE_02', 'OPS_AFTERSALE', '平台介入裁决', '处置', '/after-sales?tab=intervene', 'aftersale:ticket:handle', 'ticket:handle', 'IMPLEMENTED', 1, 'P-6.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_AFTERSALE_03', 'OPS_AFTERSALE', '极速退阈值配置', '规则', '/after-sales?tab=fastrefund', 'aftersale:refund:approve', 'order:intervene', 'IMPLEMENTED', 1, 'P-6.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_AFTERSALE_04', 'OPS_AFTERSALE', '退款回退分账', '规则', '/finance?tab=refund-back', 'finance:settle:execute', 'settle:manage', 'IMPLEMENTED', 1, 'P-6.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_MARKETING', '营销活动', 'OPS', 'Ticket', '/marketing', 80, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING_01', 'OPS_MARKETING', '券模板', '优惠券', '/marketing', 'marketing:coupon:read', 'marketing:govern', 'IMPLEMENTED', 1, 'P-7.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING_02', 'OPS_MARKETING', '发放记录', '优惠券', '/marketing?tab=issues', 'marketing:coupon:read', 'marketing:govern', 'IMPLEMENTED', 1, 'P-7.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING_03', 'OPS_MARKETING', '活动（秒杀/满减/买赠）', '活动', '/marketing?tab=campaigns', 'marketing:campaign:update', 'marketing:govern', 'IMPLEMENTED', 1, 'P-7.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING_04', 'OPS_MARKETING', '首页楼层与 Banner', '内容位', '/marketing?tab=slots', 'marketing:slot:update', 'marketing:govern', 'IMPLEMENTED', 1, 'P-7.3', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING_05', 'OPS_MARKETING', '会员卡与权益', '会员', '/marketing?tab=member', 'marketing:member:update', 'marketing:govern', 'IMPLEMENTED', 1, 'P-7.4', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_GROUP', '团购与求团', 'OPS', 'Users', '/groups', 90, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROUP_01', 'OPS_GROUP', '商家团（审核与监控）', '商家团', '/groups', 'group:campaign:audit', 'marketing:govern', 'IMPLEMENTED', 1, 'P-8.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROUP_02', 'OPS_GROUP', '需求单池与指派', '求团撮合', '/groups?tab=demands', 'group:demand:read', 'quote:govern', 'IMPLEMENTED', 1, 'P-8.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROUP_03', 'OPS_GROUP', '改价留痕与毁约', '求团撮合', '/groups?tab=quotes', 'group:demand:read', 'quote:govern', 'IMPLEMENTED', 1, 'P-8.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_GROWTH', '增长与归因', 'OPS', 'TrendingUp', '/growth', 100, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROWTH_01', 'OPS_GROWTH', '归因规则', '归因引擎', '/growth', 'growth:attribution:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-9.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROWTH_02', 'OPS_GROWTH', '归因链路审计', '归因引擎', '/growth?tab=traces', 'growth:attribution:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-9.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROWTH_03', 'OPS_GROWTH', '邀请有礼配置', '裂变活动', '/growth?tab=fission', 'growth:fission:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-9.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_FINANCE', '结算与资金', 'OPS', 'Wallet', '/finance', 110, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE_01', 'OPS_FINANCE', '结算单与分账', '分账结算', '/finance', 'finance:settle:read', 'settle:manage', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE_02', 'OPS_FINANCE', '分账明细', '分账结算', '/finance?tab=splits', 'finance:settle:read', 'settle:manage', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE_03', 'OPS_FINANCE', '退款回退分账', '分账结算', '/finance?tab=refund-back', 'finance:settle:execute', 'settle:manage', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE_04', 'OPS_FINANCE', '分档费率与服务费', '费率', '/finance?tab=rates', 'finance:rate:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-12.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE_05', 'OPS_FINANCE', '提现审批', '提现与税', '/finance?tab=withdraw', 'finance:withdraw:approve', NULL, 'NOT_IMPLEMENTED', 1, 'P-12.2', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE_06', 'OPS_FINANCE', '发票与个税', '提现与税', '/finance?tab=invoice', 'finance:invoice:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-12.2', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_REVIEW', '评价治理', 'OPS', 'Star', '/reviews', 120, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_REVIEW_01', 'OPS_REVIEW', '评价审核', '审核', '/reviews', 'review:review:audit', 'review:govern', 'IMPLEMENTED', 1, 'P-13.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_REVIEW_02', 'OPS_REVIEW', '恶意差评申诉裁决', '审核', '/reviews?tab=appeals', 'review:review:audit', 'review:govern', 'IMPLEMENTED', 1, 'P-13.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_REVIEW_03', 'OPS_REVIEW', '评分算法参数', '评分', '/reviews?tab=score', 'review:score:update', 'review:govern', 'IMPLEMENTED', 1, 'P-13.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_MESSAGE', '消息与客服', 'OPS', 'MessageSquare', '/messages', 130, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MESSAGE_01', 'OPS_MESSAGE', '消息模板与推送', '触达', '/messages', 'message:template:read', 'ticket:handle', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MESSAGE_02', 'OPS_MESSAGE', '客服工单与代客留痕', '客服', '/messages?tab=tickets', 'message:ticket:read', 'ticket:handle', 'IMPLEMENTED', 1, 'P-14.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MESSAGE_03', 'OPS_MESSAGE', '帮助中心维护', '客服', '/messages?tab=faq', 'message:faq:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-14.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_COMMUNITY', '社区与网点', 'OPS', 'MapPin', '/communities', 140, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_COMMUNITY_01', 'OPS_COMMUNITY', '社区网格', '社区网格', '/communities', 'community:community:read', 'community:view', 'IMPLEMENTED', 1, 'P-2.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_COMMUNITY_02', 'OPS_COMMUNITY', '自提点', '自提点', '/communities?tab=pickups', 'community:pickup:read', 'community:view', 'IMPLEMENTED', 1, 'P-2.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_COMMUNITY_03', 'OPS_COMMUNITY', '临时点监控', '自提点', '/communities?tab=neighbor', 'community:pickup:read', 'community:view', 'IMPLEMENTED', 1, 'P-2.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_CONTENT', '素材与内容', 'OPS', 'Images', '/contents', 150, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_CONTENT_01', 'OPS_CONTENT', '素材中心与分发', '素材', '/contents', 'content:material:read', 'content:govern', 'IMPLEMENTED', 1, 'P-15.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_CONTENT_02', 'OPS_CONTENT', '种草内容审核', '内容', '/contents?tab=audit', 'content:material:audit', 'content:govern', 'IMPLEMENTED', 1, 'P-15.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_CONTENT_03', 'OPS_CONTENT', '榜单与问答', '内容', '/contents?tab=rank', 'content:material:update', 'content:govern', 'IMPLEMENTED', 1, 'P-15.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_RISK', '风控', 'OPS', 'ShieldAlert', '/risk', 160, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_RISK_01', 'OPS_RISK', '风险事件（三类）', '识别', '/risk', 'risk:rule:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-16.2', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_RISK_02', 'OPS_RISK', '黑名单与申诉', '处置', '/risk?tab=blacklist', 'risk:blacklist:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-16.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_RISK_03', 'OPS_RISK', '拦截规则配置', '处置', '/risk?tab=rules', 'risk:rule:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-16.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_IAM', '员工与权限', 'OPS', 'UserCog', '/iam', 170, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_IAM_01', 'OPS_IAM', '员工账号与数据域', '账号', '/iam', 'iam:staff:read', 'staff:manage', 'IMPLEMENTED', 1, 'P-1.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_IAM_02', 'OPS_IAM', '角色与 RBAC', '账号', '/iam?tab=roles', 'iam:role:grant', 'staff:manage', 'IMPLEMENTED', 1, 'P-1.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_IAM_03', 'OPS_IAM', '操作审计日志', '审计', '/iam?tab=audit', 'iam:audit:read', 'audit:view', 'IMPLEMENTED', 1, 'P-1.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_SYSTEM', '系统配置', 'OPS', 'Settings', '/system', 180, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_SYSTEM_01', 'OPS_SYSTEM', '外观与规则文案', '外观与语言', '/system', 'system:theme:update', 'platform:config', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_SYSTEM_02', 'OPS_SYSTEM', '市场/货币/汇率', '外观与语言', '/system?tab=market', 'system:param:read', 'platform:config', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_SYSTEM_03', 'OPS_SYSTEM', '开关与灰度', '运行配置', '/system?tab=flags', 'system:param:read', 'platform:config', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_01', 'OPS_PRODUCT', 'product:sku:audit', '页面内操作', NULL, 'product:sku:audit', 'goods:audit', 'IMPLEMENTED', 1, NULL, 'ACTION', 901, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_02', 'OPS_PRODUCT', 'product:category:update', '页面内操作', NULL, 'product:category:update', 'category:manage', 'IMPLEMENTED', 1, NULL, 'ACTION', 902, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_03', 'OPS_SYSTEM', 'category:manage', '页面内操作', NULL, 'category:manage', 'category:manage', 'IMPLEMENTED', 1, NULL, 'ACTION', 903, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_04', 'OPS_COMMUNITY', 'community:community:update', '页面内操作', NULL, 'community:community:update', 'industry:manage', 'IMPLEMENTED', 1, NULL, 'ACTION', 904, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_05', 'OPS_COMMUNITY', 'community:pickup:update', '页面内操作', NULL, 'community:pickup:update', 'industry:manage', 'IMPLEMENTED', 1, NULL, 'ACTION', 905, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_06', 'OPS_MESSAGE', 'message:ticket:handle', '页面内操作', NULL, 'message:ticket:handle', 'ticket:handle', 'IMPLEMENTED', 1, NULL, 'ACTION', 906, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_07', 'OPS_MARKETING', 'marketing:coupon:issue', '页面内操作', NULL, 'marketing:coupon:issue', 'marketing:govern', 'IMPLEMENTED', 1, NULL, 'ACTION', 907, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_08', 'OPS_GROUP', 'group:demand:assign', '页面内操作', NULL, 'group:demand:assign', 'quote:govern', 'IMPLEMENTED', 1, NULL, 'ACTION', 908, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_09', 'OPS_MESSAGE', 'message:template:update', '页面内操作', NULL, 'message:template:update', 'ticket:handle', 'IMPLEMENTED', 1, NULL, 'ACTION', 909, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_10', 'OPS_SYSTEM', 'system:env:switch', '页面内操作', NULL, 'system:env:switch', NULL, 'NOT_IMPLEMENTED', 1, NULL, 'ACTION', 910, NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('SUPER_ADMIN', '超级管理员', 'OPS', 1, 1, 10, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_08', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_09', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_10', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('BD', '商家运营', 'OPS', 1, 0, 20, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_GROUP_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_GROUP_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_08', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('GOODS_OPS', '商品运营', 'OPS', 1, 0, 30, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_GROUP_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('SUPPORT', '客服', 'OPS', 1, 0, 40, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_MESSAGE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_MESSAGE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_09', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('CAMPAIGN_OPS', '活动运营', 'OPS', 1, 0, 50, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_GROUP_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('COMMUNITY_OPS', '社区运营', 'OPS', 1, 0, 60, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('AUDITOR', '审核员', 'OPS', 1, 0, 70, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_PRODUCT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_PRODUCT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'ACT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('FINANCE', '财务', 'OPS', 1, 0, 80, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('RISK', '风控', 'OPS', 1, 0, 90, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('ANALYST', '数据分析', 'OPS', 1, 0, 100, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('ANALYST', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('ANALYST', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('ANALYST', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES ('TECH_OPS', '技术运维', 'OPS', 1, 0, 110, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_IAM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM_03', 'OPS', NOW(), NOW());
UPDATE sys_function_point SET perm_code = 'product:sku:audit', backend_status = 'IMPLEMENTED' WHERE point_code = 'ACT_01';
UPDATE sys_function_point SET perm_code = 'product:category:update', backend_status = 'IMPLEMENTED' WHERE point_code = 'ACT_02';
UPDATE sys_function_point SET perm_code = NULL, backend_status = 'NOT_IMPLEMENTED' WHERE point_code = 'ACT_03';
UPDATE sys_function_point SET perm_code = 'community:community:update', backend_status = 'IMPLEMENTED' WHERE point_code = 'ACT_04';
UPDATE sys_function_point SET perm_code = 'community:pickup:update', backend_status = 'IMPLEMENTED' WHERE point_code = 'ACT_05';
UPDATE sys_function_point SET perm_code = 'message:ticket:handle', backend_status = 'IMPLEMENTED' WHERE point_code = 'ACT_06';
UPDATE sys_function_point SET perm_code = 'marketing:coupon:issue', backend_status = 'IMPLEMENTED' WHERE point_code = 'ACT_07';
UPDATE sys_function_point SET perm_code = 'group:demand:assign', backend_status = 'IMPLEMENTED' WHERE point_code = 'ACT_08';
UPDATE sys_function_point SET perm_code = 'message:template:update', backend_status = 'IMPLEMENTED' WHERE point_code = 'ACT_09';
UPDATE sys_function_point SET perm_code = 'aftersale:ticket:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_AFTERSALE_01';
UPDATE sys_function_point SET perm_code = 'aftersale:ticket:handle', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_AFTERSALE_02';
UPDATE sys_function_point SET perm_code = 'aftersale:refund:approve', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_AFTERSALE_03';
UPDATE sys_function_point SET perm_code = 'finance:settle:execute', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_AFTERSALE_04';
UPDATE sys_function_point SET perm_code = 'community:community:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_COMMUNITY_01';
UPDATE sys_function_point SET perm_code = 'community:pickup:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_COMMUNITY_02';
UPDATE sys_function_point SET perm_code = 'community:pickup:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_COMMUNITY_03';
UPDATE sys_function_point SET perm_code = 'content:material:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_CONTENT_01';
UPDATE sys_function_point SET perm_code = 'content:material:audit', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_CONTENT_02';
UPDATE sys_function_point SET perm_code = 'content:material:update', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_CONTENT_03';
UPDATE sys_function_point SET perm_code = 'finance:settle:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_FINANCE_01';
UPDATE sys_function_point SET perm_code = 'finance:settle:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_FINANCE_02';
UPDATE sys_function_point SET perm_code = 'finance:settle:execute', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_FINANCE_03';
UPDATE sys_function_point SET perm_code = 'finance:rate:update', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_FINANCE_04';
UPDATE sys_function_point SET perm_code = 'finance:invoice:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_FINANCE_06';
UPDATE sys_function_point SET perm_code = 'group:campaign:audit', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_GROUP_01';
UPDATE sys_function_point SET perm_code = 'group:demand:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_GROUP_02';
UPDATE sys_function_point SET perm_code = 'group:demand:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_GROUP_03';
UPDATE sys_function_point SET perm_code = 'iam:staff:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_IAM_01';
UPDATE sys_function_point SET perm_code = 'iam:role:grant', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_IAM_02';
UPDATE sys_function_point SET perm_code = 'iam:audit:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_IAM_03';
UPDATE sys_function_point SET perm_code = 'marketing:coupon:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_MARKETING_01';
UPDATE sys_function_point SET perm_code = 'marketing:coupon:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_MARKETING_02';
UPDATE sys_function_point SET perm_code = 'marketing:campaign:update', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_MARKETING_03';
UPDATE sys_function_point SET perm_code = NULL, backend_status = 'NOT_IMPLEMENTED' WHERE point_code = 'OPS_MARKETING_04';
UPDATE sys_function_point SET perm_code = NULL, backend_status = 'NOT_IMPLEMENTED' WHERE point_code = 'OPS_MARKETING_05';
UPDATE sys_function_point SET perm_code = 'merchant:apply:audit', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_MERCHANT_01';
UPDATE sys_function_point SET perm_code = 'merchant:merchant:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_MERCHANT_02';
UPDATE sys_function_point SET perm_code = 'merchant:category:grant', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_MERCHANT_03';
UPDATE sys_function_point SET perm_code = 'merchant:verify:grant', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_MERCHANT_04';
UPDATE sys_function_point SET perm_code = 'merchant:merchant:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_MERCHANT_05';
UPDATE sys_function_point SET perm_code = 'merchant:merchant:ban', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_MERCHANT_06';
UPDATE sys_function_point SET perm_code = 'message:template:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_MESSAGE_01';
UPDATE sys_function_point SET perm_code = 'message:ticket:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_MESSAGE_02';
UPDATE sys_function_point SET perm_code = 'order:order:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_ORDER_01';
UPDATE sys_function_point SET perm_code = 'order:order:modify', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_ORDER_02';
UPDATE sys_function_point SET perm_code = 'order:order:proxy', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_ORDER_03';
UPDATE sys_function_point SET perm_code = NULL, backend_status = 'NOT_IMPLEMENTED' WHERE point_code = 'OPS_ORDER_04';
UPDATE sys_function_point SET perm_code = NULL, backend_status = 'NOT_IMPLEMENTED' WHERE point_code = 'OPS_ORDER_05';
UPDATE sys_function_point SET perm_code = NULL, backend_status = 'NOT_IMPLEMENTED' WHERE point_code = 'OPS_ORDER_06';
UPDATE sys_function_point SET perm_code = 'product:category:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_PRODUCT_01';
UPDATE sys_function_point SET perm_code = 'product:sku:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_PRODUCT_02';
UPDATE sys_function_point SET perm_code = NULL, backend_status = 'NOT_IMPLEMENTED' WHERE point_code = 'OPS_PRODUCT_03';
UPDATE sys_function_point SET perm_code = 'review:review:audit', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_REVIEW_01';
UPDATE sys_function_point SET perm_code = 'review:review:audit', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_REVIEW_02';
UPDATE sys_function_point SET perm_code = 'review:score:update', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_REVIEW_03';
UPDATE sys_function_point SET perm_code = 'store:page:audit', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_STORE_01';
UPDATE sys_function_point SET perm_code = 'system:theme:update', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_SYSTEM_01';
UPDATE sys_function_point SET perm_code = 'system:param:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_SYSTEM_02';
UPDATE sys_function_point SET perm_code = 'system:param:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_SYSTEM_03';
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_11', 'OPS_AFTERSALE', 'aftersale:refund:read', '无界面入口', NULL, 'aftersale:refund:read', 'aftersale:refund:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 911, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_12', 'OPS_COMMUNITY', 'community:region:read', '无界面入口', NULL, 'community:region:read', 'community:region:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 912, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_13', 'OPS_DASHBOARD', 'dashboard:overview:read', '无界面入口', NULL, 'dashboard:overview:read', 'dashboard:overview:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 913, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_14', 'OPS_FINANCE', 'finance:invoice:verify', '无界面入口', NULL, 'finance:invoice:verify', 'finance:invoice:verify', 'IMPLEMENTED', 0, NULL, 'ACTION', 914, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_15', 'OPS_FINANCE', 'finance:payout:execute', '无界面入口', NULL, 'finance:payout:execute', 'finance:payout:execute', 'IMPLEMENTED', 0, NULL, 'ACTION', 915, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_16', 'OPS_FINANCE', 'finance:rate:read', '无界面入口', NULL, 'finance:rate:read', 'finance:rate:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 916, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_17', 'OPS_FINANCE', 'finance:recon:read', '无界面入口', NULL, 'finance:recon:read', 'finance:recon:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 917, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_18', 'OPS_FINANCE', 'finance:recon:resolve', '无界面入口', NULL, 'finance:recon:resolve', 'finance:recon:resolve', 'IMPLEMENTED', 0, NULL, 'ACTION', 918, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_19', 'OPS_GROUP', 'group:campaign:read', '无界面入口', NULL, 'group:campaign:read', 'group:campaign:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 919, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_20', 'OPS_IAM', 'iam:role:read', '无界面入口', NULL, 'iam:role:read', 'iam:role:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 920, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_21', 'OPS_IAM', 'iam:staff:update', '无界面入口', NULL, 'iam:staff:update', 'iam:staff:update', 'IMPLEMENTED', 0, NULL, 'ACTION', 921, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_22', 'OPS_MARKETING', 'marketing:campaign:read', '无界面入口', NULL, 'marketing:campaign:read', 'marketing:campaign:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 922, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_23', 'OPS_MARKETING', 'marketing:coupon:update', '无界面入口', NULL, 'marketing:coupon:update', 'marketing:coupon:update', 'IMPLEMENTED', 0, NULL, 'ACTION', 923, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_24', 'OPS_MERCHANT', 'merchant:admission:read', '无界面入口', NULL, 'merchant:admission:read', 'merchant:admission:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 924, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_25', 'OPS_MERCHANT', 'merchant:admission:update', '无界面入口', NULL, 'merchant:admission:update', 'merchant:admission:update', 'IMPLEMENTED', 0, NULL, 'ACTION', 925, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_26', 'OPS_MERCHANT', 'merchant:category:read', '无界面入口', NULL, 'merchant:category:read', 'merchant:category:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 926, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_27', 'OPS_MERCHANT', 'merchant:mode:read', '无界面入口', NULL, 'merchant:mode:read', 'merchant:mode:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 927, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_28', 'OPS_MERCHANT', 'merchant:mode:update', '无界面入口', NULL, 'merchant:mode:update', 'merchant:mode:update', 'IMPLEMENTED', 0, NULL, 'ACTION', 928, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_29', 'OPS_REVIEW', 'review:review:read', '无界面入口', NULL, 'review:review:read', 'review:review:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 929, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_30', 'OPS_REVIEW', 'review:score:read', '无界面入口', NULL, 'review:score:read', 'review:score:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 930, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_31', 'OPS_SYSTEM', 'system:industry:read', '无界面入口', NULL, 'system:industry:read', 'system:industry:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 931, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_32', 'OPS_SYSTEM', 'system:industry:update', '无界面入口', NULL, 'system:industry:update', 'system:industry:update', 'IMPLEMENTED', 0, NULL, 'ACTION', 932, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_33', 'OPS_SYSTEM', 'system:param:update', '无界面入口', NULL, 'system:param:update', 'system:param:update', 'IMPLEMENTED', 0, NULL, 'ACTION', 933, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT_34', 'OPS_SYSTEM', 'system:theme:read', '无界面入口', NULL, 'system:theme:read', 'system:theme:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 934, NOW(), NOW());
DELETE FROM sys_role_point WHERE end_code = 'OPS' AND role_code IN ('SUPER_ADMIN', 'BD', 'GOODS_OPS', 'SUPPORT', 'CAMPAIGN_OPS', 'COMMUNITY_OPS', 'AUDITOR', 'FINANCE', 'RISK', 'ANALYST', 'TECH_OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_08', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_09', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_10', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_12', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_14', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_15', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_16', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_17', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_18', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_19', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_20', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_21', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_22', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_23', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_24', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_25', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_26', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_27', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_28', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_29', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_30', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_31', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_32', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_33', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_34', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_08', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_AFTERSALE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_GROUP_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_GROUP_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_STORE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_26', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_AFTERSALE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_GROUP_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_19', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_22', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_23', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_09', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_MESSAGE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_MESSAGE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_29', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_30', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_AFTERSALE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_GROUP_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_19', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_22', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_23', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_AFTERSALE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_12', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_31', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_32', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'ACT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_PRODUCT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'ACT_29', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'ACT_30', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_14', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_15', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_16', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_17', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_18', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_24', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_25', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_27', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_28', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_AFTERSALE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('ANALYST', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_IAM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'ACT_33', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'ACT_34', 'OPS', NOW(), NOW());
DELETE FROM sys_role_point WHERE end_code = 'OPS' AND role_code IN ('SUPER_ADMIN', 'BD', 'GOODS_OPS', 'SUPPORT', 'CAMPAIGN_OPS', 'COMMUNITY_OPS', 'AUDITOR', 'FINANCE', 'RISK', 'ANALYST', 'TECH_OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_08', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_09', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_12', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_14', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_15', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_16', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_17', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_18', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_19', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_20', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_21', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_22', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_23', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_24', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_25', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_26', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_27', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_28', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_29', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_30', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_31', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_32', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_33', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_34', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_10', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_08', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_GROUP_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_GROUP_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_STORE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_26', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_27', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_28', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_GROUP_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_19', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_22', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_23', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_09', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_MESSAGE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_MESSAGE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_29', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_30', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_GROUP_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_19', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_22', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_23', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_12', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_31', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_32', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'ACT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_PRODUCT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'ACT_29', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'ACT_30', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_14', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_15', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_16', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_17', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_18', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_24', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_25', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('ANALYST', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_IAM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'ACT_33', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'ACT_34', 'OPS', NOW(), NOW());
UPDATE sys_function_point SET perm_code = 'product:category:update', backend_status = 'IMPLEMENTED' WHERE point_code = 'ACT_03';
UPDATE sys_function_point SET perm_code = 'order:order:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_ORDER_04';
UPDATE sys_function_point SET perm_code = 'order:order:modify', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_ORDER_05';
UPDATE sys_function_point SET perm_code = 'order:order:modify', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_ORDER_06';
UPDATE sys_function_point SET perm_code = 'product:sku:audit', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_PRODUCT_03';
DELETE FROM sys_role_point WHERE end_code = 'OPS' AND role_code IN ('SUPER_ADMIN', 'BD', 'GOODS_OPS', 'SUPPORT', 'CAMPAIGN_OPS', 'COMMUNITY_OPS', 'AUDITOR', 'FINANCE', 'RISK', 'ANALYST', 'TECH_OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_08', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_09', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_12', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_14', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_15', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_16', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_17', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_18', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_19', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_20', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_21', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_22', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_23', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_24', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_25', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_26', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_27', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_28', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_29', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_30', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_31', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_32', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_33', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_34', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_10', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_08', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_GROUP_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_GROUP_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_STORE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_26', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_27', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_28', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_GROUP_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_19', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_22', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_23', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_09', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_MESSAGE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_MESSAGE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_29', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_30', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_GROUP_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_19', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_22', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_23', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_12', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_31', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_32', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'ACT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_PRODUCT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_PRODUCT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'ACT_29', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'ACT_30', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_14', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_15', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_16', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_17', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_18', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_24', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_25', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('ANALYST', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_IAM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'ACT_33', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'ACT_34', 'OPS', NOW(), NOW());
UPDATE sys_function_point SET ui_kind = 'MENU'   WHERE point_type = 'MENU';
UPDATE sys_function_point SET ui_kind = 'INLINE' WHERE point_type = 'ACTION' AND group_name = '页面内操作';
UPDATE sys_function_point SET ui_kind = 'NONE'   WHERE point_type = 'ACTION' AND group_name = '无界面入口';
INSERT INTO mch_role (entity_no, role_code, name, perms, builtin, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('*', 'OWNER', '老板', '["*"]', 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('*', 'MANAGER', '店长', '["biz:receive","biz:verify","biz:ship","biz:order:view","biz:stock","biz:goods","biz:campaign","biz:review","biz:aftersale","biz:customer","biz:store"]', 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('*', 'CLERK', '店员', '["biz:receive","biz:verify","biz:ship","biz:order:view","biz:stock"]', 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('*', 'PICKER', '理货员', '["biz:receive","biz:stock"]', 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('*', 'COURIER', '配送员', '["biz:ship","biz:order:view"]', 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('*', 'CS', '客服', '["biz:review","biz:aftersale","biz:order:view"]', 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
DELETE FROM sys_role_point     WHERE end_code = 'OPS';
DELETE FROM sys_function_point WHERE function_code LIKE 'OPS\_%';
DELETE FROM sys_function       WHERE end_code = 'OPS';
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_DASHBOARD', '经营看板', 'OPS', 'LayoutDashboard', '/', 10, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_MERCHANT', '商家治理', 'OPS', 'Store', '/merchants', 20, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_STORE', '门店主页', 'OPS', 'LayoutTemplate', '/stores', 30, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_PRODUCT', '商品与类目', 'OPS', 'Package', '/products', 40, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_ORDER', '交易订单', 'OPS', 'ReceiptText', '/orders', 50, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_FULFILLMENT', '履约调度', 'OPS', 'Truck', '/fulfillment', 60, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_AFTERSALE', '售后治理', 'OPS', 'Undo2', '/after-sales', 70, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_MARKETING', '营销活动', 'OPS', 'Ticket', '/marketing', 80, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_GROUP', '团购与求团', 'OPS', 'Users', '/groups', 90, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_GROWTH', '增长与归因', 'OPS', 'TrendingUp', '/growth', 100, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_FINANCE', '结算与资金', 'OPS', 'Wallet', '/finance', 110, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_REVIEW', '评价治理', 'OPS', 'Star', '/reviews', 120, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_MESSAGE', '消息与客服', 'OPS', 'MessageSquare', '/messages', 130, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_COMMUNITY', '社区与网点', 'OPS', 'MapPin', '/communities', 140, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_CONTENT', '素材与内容', 'OPS', 'Images', '/contents', 150, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_RISK', '风控', 'OPS', 'ShieldAlert', '/risk', 160, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_IAM', '员工与权限', 'OPS', 'UserCog', '/iam', 170, 1, NOW(), NOW());
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES ('OPS_SYSTEM', '系统配置', 'OPS', 'Settings', '/system', 180, 1, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT', 'OPS_MERCHANT', '入驻审核', '入驻与资质', '/merchants', 'merchant:apply:audit', 'merchant:apply:audit', 'IMPLEMENTED', 1, 'P-11.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT__TAB_LIST', 'OPS_MERCHANT', '商家档案', '入驻与资质', '/merchants?tab=list', 'merchant:merchant:read', 'merchant:merchant:read', 'IMPLEMENTED', 1, 'P-11.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT__TAB_CATEGORIES', 'OPS_MERCHANT', '类目授权', '入驻与资质', '/merchants?tab=categories', 'merchant:category:grant', 'merchant:category:grant', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT__TAB_ADMISSION', 'OPS_MERCHANT', '准入与保证金', '入驻与资质', '/merchants?tab=admission', 'merchant:admission:read', NULL, 'UNMAPPED', 0, 'P-11.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT__TAB_VERIFY', 'OPS_MERCHANT', '认证标管理', '信用与处置', '/merchants?tab=verify', 'merchant:verify:grant', 'merchant:verify:grant', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT__TAB_CREDIT', 'OPS_MERCHANT', '信用档案', '信用与处置', '/merchants?tab=credit', 'merchant:merchant:read', 'merchant:merchant:read', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT__TAB_BAN', 'OPS_MERCHANT', '违规处置与封禁', '信用与处置', '/merchants?tab=ban', 'merchant:merchant:ban', 'merchant:merchant:ban', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 70, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_STORE', 'OPS_STORE', '店招公告审核', '模板与合规', '/stores', 'store:page:audit', 'store:page:audit', 'IMPLEMENTED', 1, 'P-10.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_STORE__TAB_TEMPLATE', 'OPS_STORE', '主页模板配置', '模板与合规', '/stores?tab=template', 'store:page:read', NULL, 'NOT_IMPLEMENTED', 0, 'P-10.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_STORE__TAB_QRCODE', 'OPS_STORE', '店铺码生成导出', '获客', '/stores?tab=qrcode', 'store:qrcode:export', NULL, 'NOT_IMPLEMENTED', 1, 'P-10.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_STORE__TAB_EFFECT', 'OPS_STORE', '获客效果看板', '获客', '/stores?tab=effect', 'store:page:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-10.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_PRODUCT', 'OPS_PRODUCT', '三级类目树', '类目', '/products', 'product:category:read', 'product:category:read', 'IMPLEMENTED', 1, 'P-3.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_PRODUCT__TAB_SKUS', 'OPS_PRODUCT', '商品池与审核', '商品', '/products?tab=skus', 'product:sku:read', 'product:sku:read', 'IMPLEMENTED', 1, 'P-3.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_PRODUCT__TAB_AUDIT', 'OPS_PRODUCT', '商品审核队列', '商品', '/products?tab=audit', 'product:sku:audit', 'product:sku:audit', 'IMPLEMENTED', 1, 'P-3.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_PRODUCT__TAB_STOCK', 'OPS_PRODUCT', '预售额度与超卖', '库存与预售', '/products?tab=stock', 'product:stock:update', 'product:sku:audit', 'IMPLEMENTED', 1, 'P-3.3', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER', 'OPS_ORDER', '订单检索', '订单', '/orders', 'order:order:read', 'order:order:read', 'IMPLEMENTED', 1, 'P-4.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER__TAB_EXCEPTION', 'OPS_ORDER', '异常单处理', '订单', '/orders?tab=exception', 'order:order:modify', 'order:order:modify', 'IMPLEMENTED', 0, 'P-4.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER__TAB_PROXY', 'OPS_ORDER', '代客下单/取消', '订单', '/orders?tab=proxy', 'order:order:proxy', 'order:order:proxy', 'IMPLEMENTED', 0, 'P-4.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER__TAB_PAY', 'OPS_ORDER', '支付流水核对', '支付', '/orders?tab=pay', 'order:pay:read', 'order:order:read', 'IMPLEMENTED', 0, 'P-4.2', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER__TAB_REPAIR', 'OPS_ORDER', '掉单补偿', '支付', '/orders?tab=repair', 'order:pay:repair', 'order:order:modify', 'IMPLEMENTED', 0, 'P-4.2', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_ORDER__TAB_CLOSE', 'OPS_ORDER', '关单策略配置', '支付', '/orders?tab=close', 'order:pay:repair', 'order:order:modify', 'IMPLEMENTED', 0, 'P-4.2', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT', 'OPS_FULFILLMENT', '到货批次与配车', '到货与分拣', '/fulfillment', 'fulfillment:batch:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT__TAB_SORTING', 'OPS_FULFILLMENT', '按自提点汇总分拣', '到货与分拣', '/fulfillment?tab=sorting', 'fulfillment:batch:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT__TAB_REDEEM', 'OPS_FULFILLMENT', '核销监控与逾期', '核销', '/fulfillment?tab=redeem', 'fulfillment:redeem:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT__TAB_OVERDUE', 'OPS_FULFILLMENT', '逾期规则配置', '核销', '/fulfillment?tab=overdue', 'fulfillment:rule:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT__TAB_EXPRESS', 'OPS_FULFILLMENT', '快递与轨迹', '物流', '/fulfillment?tab=express', 'fulfillment:logistics:read', NULL, 'NOT_IMPLEMENTED', 0, 'P-5.2', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT__TAB_FREIGHT', 'OPS_FULFILLMENT', '运费模板与超区', '物流', '/fulfillment?tab=freight', 'fulfillment:rule:update', NULL, 'NOT_IMPLEMENTED', 0, 'P-5.2', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FULFILLMENT__TAB_CARRIER', 'OPS_FULFILLMENT', '第三方运力配置', '物流', '/fulfillment?tab=carrier', 'fulfillment:logistics:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-5.2', 'MENU', 70, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_AFTERSALE', 'OPS_AFTERSALE', '售后工单池', '处置', '/after-sales', 'aftersale:ticket:read', 'aftersale:ticket:read', 'IMPLEMENTED', 1, 'P-6.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_AFTERSALE__TAB_INTERVENE', 'OPS_AFTERSALE', '平台介入裁决', '处置', '/after-sales?tab=intervene', 'aftersale:ticket:handle', 'aftersale:ticket:handle', 'IMPLEMENTED', 1, 'P-6.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_AFTERSALE__TAB_FASTREFUND', 'OPS_AFTERSALE', '极速退阈值配置', '规则', '/after-sales?tab=fastrefund', 'aftersale:refund:approve', 'aftersale:refund:approve', 'IMPLEMENTED', 1, 'P-6.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_AFTERSALE__TAB_REFUND_BACK', 'OPS_AFTERSALE', '退款回退分账', '规则', '/finance?tab=refund-back', 'finance:settle:execute', 'finance:settle:execute', 'IMPLEMENTED', 1, 'P-6.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING', 'OPS_MARKETING', '券模板', '优惠券', '/marketing', 'marketing:coupon:read', 'marketing:coupon:read', 'IMPLEMENTED', 1, 'P-7.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING__TAB_ISSUES', 'OPS_MARKETING', '发放记录', '优惠券', '/marketing?tab=issues', 'marketing:coupon:read', 'marketing:coupon:read', 'IMPLEMENTED', 1, 'P-7.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING__TAB_CAMPAIGNS', 'OPS_MARKETING', '活动', '活动', '/marketing?tab=campaigns', 'marketing:campaign:update', 'marketing:campaign:update', 'IMPLEMENTED', 1, 'P-7.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING__TAB_SLOTS', 'OPS_MARKETING', '首页楼层与 Banner', '内容位', '/marketing?tab=slots', 'marketing:slot:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-7.3', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MARKETING__TAB_MEMBER', 'OPS_MARKETING', '会员卡与权益', '会员', '/marketing?tab=member', 'marketing:member:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-7.4', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROUP', 'OPS_GROUP', '商家团', '商家团', '/groups', 'group:campaign:audit', 'group:campaign:audit', 'IMPLEMENTED', 1, 'P-8.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROUP__TAB_DEMANDS', 'OPS_GROUP', '需求单池与指派', '求团撮合', '/groups?tab=demands', 'group:demand:read', 'group:demand:read', 'IMPLEMENTED', 1, 'P-8.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROUP__TAB_QUOTES', 'OPS_GROUP', '改价留痕与毁约', '求团撮合', '/groups?tab=quotes', 'group:demand:read', 'group:demand:read', 'IMPLEMENTED', 1, 'P-8.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROWTH', 'OPS_GROWTH', '归因规则', '归因引擎', '/growth', 'growth:attribution:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-9.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROWTH__TAB_TRACES', 'OPS_GROWTH', '归因链路审计', '归因引擎', '/growth?tab=traces', 'growth:attribution:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-9.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_GROWTH__TAB_FISSION', 'OPS_GROWTH', '邀请有礼配置', '裂变活动', '/growth?tab=fission', 'growth:fission:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-9.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE', 'OPS_FINANCE', '结算单与分账', '分账结算', '/finance', 'finance:settle:read', 'finance:settle:read', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE__TAB_SPLITS', 'OPS_FINANCE', '分账明细', '分账结算', '/finance?tab=splits', 'finance:settle:read', 'finance:settle:read', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE__TAB_REFUND_BACK', 'OPS_FINANCE', '退款回退分账', '分账结算', '/finance?tab=refund-back', 'finance:settle:execute', 'finance:settle:execute', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE__TAB_RATES', 'OPS_FINANCE', '分档费率与服务费', '费率', '/finance?tab=rates', 'finance:rate:update', 'finance:rate:update', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE__TAB_WITHDRAW', 'OPS_FINANCE', '提现审批', '提现与税', '/finance?tab=withdraw', 'finance:withdraw:approve', NULL, 'NOT_IMPLEMENTED', 1, 'P-12.2', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE__TAB_INVOICE', 'OPS_FINANCE', '发票与个税', '提现与税', '/finance?tab=invoice', 'finance:invoice:read', 'finance:invoice:read', 'IMPLEMENTED', 1, 'P-12.2', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_REVIEW', 'OPS_REVIEW', '评价审核', '审核', '/reviews', 'review:review:audit', 'review:review:audit', 'IMPLEMENTED', 1, 'P-13.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_REVIEW__TAB_APPEALS', 'OPS_REVIEW', '恶意差评申诉裁决', '审核', '/reviews?tab=appeals', 'review:review:audit', 'review:review:audit', 'IMPLEMENTED', 1, 'P-13.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_REVIEW__TAB_SCORE', 'OPS_REVIEW', '评分算法参数', '评分', '/reviews?tab=score', 'review:score:update', 'review:score:update', 'IMPLEMENTED', 1, 'P-13.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MESSAGE', 'OPS_MESSAGE', '消息模板与推送', '触达', '/messages', 'message:template:read', 'message:template:read', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MESSAGE__TAB_TICKETS', 'OPS_MESSAGE', '客服工单与代客留痕', '客服', '/messages?tab=tickets', 'message:ticket:read', 'message:ticket:read', 'IMPLEMENTED', 1, 'P-14.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MESSAGE__TAB_FAQ', 'OPS_MESSAGE', '帮助中心维护', '客服', '/messages?tab=faq', 'message:faq:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-14.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_COMMUNITY', 'OPS_COMMUNITY', '社区网格', '社区网格', '/communities', 'community:community:read', 'community:community:read', 'IMPLEMENTED', 1, 'P-2.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_COMMUNITY__TAB_APPLIES', 'OPS_COMMUNITY', '商家提报', '社区网格', '/communities?tab=applies', 'community:community:read', 'community:community:read', 'IMPLEMENTED', 1, 'P-2.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_COMMUNITY__TAB_PICKUPS', 'OPS_COMMUNITY', '自提点', '自提点', '/communities?tab=pickups', 'community:pickup:read', 'community:pickup:read', 'IMPLEMENTED', 1, 'P-2.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_COMMUNITY__TAB_NEIGHBOR', 'OPS_COMMUNITY', '临时点监控', '自提点', '/communities?tab=neighbor', 'community:pickup:read', 'community:pickup:read', 'IMPLEMENTED', 1, 'P-2.2', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_CONTENT', 'OPS_CONTENT', '素材中心与分发', '素材', '/contents', 'content:material:read', 'content:material:read', 'IMPLEMENTED', 1, 'P-15.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_CONTENT__TAB_AUDIT', 'OPS_CONTENT', '种草内容审核', '内容', '/contents?tab=audit', 'content:material:audit', 'content:material:audit', 'IMPLEMENTED', 1, 'P-15.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_CONTENT__TAB_RANK', 'OPS_CONTENT', '榜单与问答', '内容', '/contents?tab=rank', 'content:material:update', 'content:material:update', 'IMPLEMENTED', 1, 'P-15.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_RISK', 'OPS_RISK', '风险事件', '识别', '/risk', 'risk:rule:read', NULL, 'NOT_IMPLEMENTED', 1, 'P-16.2', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_RISK__TAB_BLACKLIST', 'OPS_RISK', '黑名单与申诉', '处置', '/risk?tab=blacklist', 'risk:blacklist:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-16.2', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_RISK__TAB_RULES', 'OPS_RISK', '拦截规则配置', '处置', '/risk?tab=rules', 'risk:rule:update', NULL, 'NOT_IMPLEMENTED', 1, 'P-16.2', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_IAM', 'OPS_IAM', '员工账号与数据域', '账号', '/iam', 'iam:staff:read', 'iam:staff:read', 'IMPLEMENTED', 1, 'P-1.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_IAM__TAB_ROLES', 'OPS_IAM', '角色与权限', '账号', '/iam?tab=roles', 'iam:role:grant', 'iam:role:grant', 'IMPLEMENTED', 1, 'P-1.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_IAM__TAB_AUDIT', 'OPS_IAM', '操作审计日志', '审计', '/iam?tab=audit', 'iam:audit:read', 'iam:audit:read', 'IMPLEMENTED', 1, 'P-1.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_SYSTEM', 'OPS_SYSTEM', '外观与规则文案', '外观与语言', '/system', 'system:theme:update', 'system:theme:update', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 10, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_SYSTEM__TAB_MARKET', 'OPS_SYSTEM', '市场/货币/汇率', '外观与语言', '/system?tab=market', 'system:param:read', 'system:param:read', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 20, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_SYSTEM__TAB_FLAGS', 'OPS_SYSTEM', '开关与灰度', '运行配置', '/system?tab=flags', 'system:param:read', 'system:param:read', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 30, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_SYSTEM__TAB_INDUSTRY', 'OPS_SYSTEM', '行业与小微白名单', '经营范围', '/system?tab=industry', 'system:param:read', 'system:param:read', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 40, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_SYSTEM__TAB_AUTHCODE', 'OPS_SYSTEM', '经营授权码', '经营范围', '/system?tab=authCode', 'system:param:read', 'system:param:read', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 50, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_SYSTEM__TAB_SCOPE', 'OPS_SYSTEM', '经营范围开关', '经营范围', '/system?tab=scope', 'system:param:read', 'system:param:read', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__PRODUCT_CATEGORY_UPDATE', 'OPS_PRODUCT', 'product:category:update', '页面内操作', NULL, 'product:category:update', 'product:category:update', 'IMPLEMENTED', 1, NULL, 'ACTION', 901, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__CATEGORY_MANAGE', 'OPS_SYSTEM', 'category:manage', '页面内操作', NULL, 'category:manage', 'product:category:update', 'IMPLEMENTED', 1, NULL, 'ACTION', 902, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__COMMUNITY_COMMUNITY_UPDATE', 'OPS_COMMUNITY', 'community:community:update', '页面内操作', NULL, 'community:community:update', 'community:community:update', 'IMPLEMENTED', 1, NULL, 'ACTION', 903, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__COMMUNITY_PICKUP_UPDATE', 'OPS_COMMUNITY', 'community:pickup:update', '页面内操作', NULL, 'community:pickup:update', 'community:pickup:update', 'IMPLEMENTED', 1, NULL, 'ACTION', 904, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__MESSAGE_TICKET_HANDLE', 'OPS_MESSAGE', 'message:ticket:handle', '页面内操作', NULL, 'message:ticket:handle', 'message:ticket:handle', 'IMPLEMENTED', 1, NULL, 'ACTION', 905, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__MARKETING_COUPON_ISSUE', 'OPS_MARKETING', 'marketing:coupon:issue', '页面内操作', NULL, 'marketing:coupon:issue', 'marketing:coupon:issue', 'IMPLEMENTED', 1, NULL, 'ACTION', 906, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__GROUP_DEMAND_ASSIGN', 'OPS_GROUP', 'group:demand:assign', '页面内操作', NULL, 'group:demand:assign', 'group:demand:assign', 'IMPLEMENTED', 1, NULL, 'ACTION', 907, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__MESSAGE_TEMPLATE_UPDATE', 'OPS_MESSAGE', 'message:template:update', '页面内操作', NULL, 'message:template:update', 'message:template:update', 'IMPLEMENTED', 1, NULL, 'ACTION', 908, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__SYSTEM_ENV_SWITCH', 'OPS_SYSTEM', 'system:env:switch', '页面内操作', NULL, 'system:env:switch', NULL, 'NOT_IMPLEMENTED', 1, NULL, 'ACTION', 909, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT__TAB_LIST', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT__TAB_CATEGORIES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT__TAB_ADMISSION', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT__TAB_VERIFY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT__TAB_CREDIT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT__TAB_BAN', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE__TAB_TEMPLATE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE__TAB_QRCODE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE__TAB_EFFECT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT__TAB_SKUS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT__TAB_AUDIT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT__TAB_STOCK', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER__TAB_EXCEPTION', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER__TAB_PROXY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER__TAB_PAY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER__TAB_REPAIR', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER__TAB_CLOSE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT__TAB_SORTING', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT__TAB_REDEEM', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT__TAB_OVERDUE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT__TAB_EXPRESS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT__TAB_FREIGHT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT__TAB_CARRIER', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE__TAB_INTERVENE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE__TAB_FASTREFUND', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE__TAB_REFUND_BACK', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING__TAB_ISSUES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING__TAB_CAMPAIGNS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING__TAB_SLOTS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING__TAB_MEMBER', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP__TAB_DEMANDS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP__TAB_QUOTES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH__TAB_TRACES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH__TAB_FISSION', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE__TAB_SPLITS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE__TAB_REFUND_BACK', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE__TAB_RATES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE__TAB_WITHDRAW', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE__TAB_INVOICE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW__TAB_APPEALS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW__TAB_SCORE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE__TAB_TICKETS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE__TAB_FAQ', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY__TAB_APPLIES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY__TAB_PICKUPS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY__TAB_NEIGHBOR', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT__TAB_AUDIT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT__TAB_RANK', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK__TAB_BLACKLIST', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK__TAB_RULES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM__TAB_ROLES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM__TAB_AUDIT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM__TAB_MARKET', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM__TAB_FLAGS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM__TAB_INDUSTRY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM__TAB_AUTHCODE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM__TAB_SCOPE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__PRODUCT_CATEGORY_UPDATE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__CATEGORY_MANAGE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__COMMUNITY_COMMUNITY_UPDATE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__COMMUNITY_PICKUP_UPDATE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__MESSAGE_TICKET_HANDLE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__MARKETING_COUPON_ISSUE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__GROUP_DEMAND_ASSIGN', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__MESSAGE_TEMPLATE_UPDATE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__SYSTEM_ENV_SWITCH', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT__TAB_LIST', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT__TAB_CATEGORIES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT__TAB_VERIFY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT__TAB_CREDIT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT__TAB_BAN', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_STORE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_ORDER', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_ORDER__TAB_PAY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_AFTERSALE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_GROUP__TAB_DEMANDS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_GROUP__TAB_QUOTES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_COMMUNITY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_COMMUNITY__TAB_APPLIES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT__GROUP_DEMAND_ASSIGN', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT__TAB_SKUS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT__TAB_AUDIT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT__TAB_STOCK', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_ORDER', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_ORDER__TAB_PAY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_AFTERSALE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING__TAB_ISSUES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING__TAB_CAMPAIGNS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_GROUP', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_COMMUNITY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_COMMUNITY__TAB_APPLIES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT__PRODUCT_CATEGORY_UPDATE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT__CATEGORY_MANAGE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT__MARKETING_COUPON_ISSUE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER__TAB_EXCEPTION', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER__TAB_PROXY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER__TAB_PAY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER__TAB_REPAIR', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER__TAB_CLOSE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE__TAB_INTERVENE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE__TAB_FASTREFUND', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW__TAB_APPEALS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW__TAB_SCORE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_MESSAGE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_MESSAGE__TAB_TICKETS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_COMMUNITY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_COMMUNITY__TAB_APPLIES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT__MESSAGE_TICKET_HANDLE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT__MESSAGE_TEMPLATE_UPDATE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_ORDER', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_ORDER__TAB_PAY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_AFTERSALE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING__TAB_ISSUES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING__TAB_CAMPAIGNS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_GROUP', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_COMMUNITY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_COMMUNITY__TAB_APPLIES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT__TAB_AUDIT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT__TAB_RANK', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT__MARKETING_COUPON_ISSUE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_ORDER', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_ORDER__TAB_PAY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_AFTERSALE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY__TAB_APPLIES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY__TAB_PICKUPS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY__TAB_NEIGHBOR', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT__COMMUNITY_COMMUNITY_UPDATE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT__COMMUNITY_PICKUP_UPDATE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_PRODUCT__TAB_SKUS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_PRODUCT__TAB_AUDIT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_PRODUCT__TAB_STOCK', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW__TAB_APPEALS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW__TAB_SCORE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_COMMUNITY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_COMMUNITY__TAB_APPLIES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT__TAB_AUDIT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT__TAB_RANK', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_ORDER', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_ORDER__TAB_PAY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE__TAB_FASTREFUND', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE__TAB_REFUND_BACK', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE__TAB_SPLITS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE__TAB_REFUND_BACK', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE__TAB_RATES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE__TAB_INVOICE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_ORDER', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_ORDER__TAB_PAY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_AFTERSALE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('ANALYST', 'OPS_COMMUNITY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('ANALYST', 'OPS_COMMUNITY__TAB_APPLIES', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_IAM__TAB_AUDIT', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM__TAB_MARKET', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM__TAB_FLAGS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM__TAB_INDUSTRY', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM__TAB_AUTHCODE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM__TAB_SCOPE', 'OPS', NOW(), NOW());
UPDATE sys_function_point
   SET ui_perm_code  = 'merchant:merchant:read',
       perm_code     = 'merchant:merchant:read',
       backend_status = 'IMPLEMENTED'
 WHERE point_code = 'OPS_MERCHANT__TAB_ADMISSION';
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__AFTERSALE_REFUND_READ', 'OPS_AFTERSALE', 'aftersale:refund:read', '仅后端', NULL, NULL, 'aftersale:refund:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 910, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__COMMUNITY_REGION_READ', 'OPS_COMMUNITY', 'community:region:read', '仅后端', NULL, NULL, 'community:region:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 911, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__DASHBOARD_OVERVIEW_READ', 'OPS_DASHBOARD', 'dashboard:overview:read', '仅后端', NULL, NULL, 'dashboard:overview:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 912, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__FINANCE_INVOICE_VERIFY', 'OPS_FINANCE', 'finance:invoice:verify', '仅后端', NULL, NULL, 'finance:invoice:verify', 'IMPLEMENTED', 0, NULL, 'ACTION', 913, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__FINANCE_PAYOUT_EXECUTE', 'OPS_FINANCE', 'finance:payout:execute', '仅后端', NULL, NULL, 'finance:payout:execute', 'IMPLEMENTED', 0, NULL, 'ACTION', 914, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__FINANCE_RATE_READ', 'OPS_FINANCE', 'finance:rate:read', '仅后端', NULL, NULL, 'finance:rate:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 915, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__FINANCE_RECON_READ', 'OPS_FINANCE', 'finance:recon:read', '仅后端', NULL, NULL, 'finance:recon:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 916, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__FINANCE_RECON_RESOLVE', 'OPS_FINANCE', 'finance:recon:resolve', '仅后端', NULL, NULL, 'finance:recon:resolve', 'IMPLEMENTED', 0, NULL, 'ACTION', 917, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__GROUP_CAMPAIGN_READ', 'OPS_GROUP', 'group:campaign:read', '仅后端', NULL, NULL, 'group:campaign:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 918, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__MARKETING_CAMPAIGN_READ', 'OPS_MARKETING', 'marketing:campaign:read', '仅后端', NULL, NULL, 'marketing:campaign:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 919, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__MARKETING_COUPON_UPDATE', 'OPS_MARKETING', 'marketing:coupon:update', '仅后端', NULL, NULL, 'marketing:coupon:update', 'IMPLEMENTED', 0, NULL, 'ACTION', 920, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__MERCHANT_ADMISSION_READ', 'OPS_MERCHANT', 'merchant:admission:read', '仅后端', NULL, NULL, 'merchant:admission:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 921, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__MERCHANT_ADMISSION_UPDATE', 'OPS_MERCHANT', 'merchant:admission:update', '仅后端', NULL, NULL, 'merchant:admission:update', 'IMPLEMENTED', 0, NULL, 'ACTION', 922, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__MERCHANT_CATEGORY_READ', 'OPS_MERCHANT', 'merchant:category:read', '仅后端', NULL, NULL, 'merchant:category:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 923, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__MERCHANT_MODE_READ', 'OPS_MERCHANT', 'merchant:mode:read', '仅后端', NULL, NULL, 'merchant:mode:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 924, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__MERCHANT_MODE_UPDATE', 'OPS_MERCHANT', 'merchant:mode:update', '仅后端', NULL, NULL, 'merchant:mode:update', 'IMPLEMENTED', 0, NULL, 'ACTION', 925, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__REVIEW_REVIEW_READ', 'OPS_REVIEW', 'review:review:read', '仅后端', NULL, NULL, 'review:review:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 926, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__REVIEW_SCORE_READ', 'OPS_REVIEW', 'review:score:read', '仅后端', NULL, NULL, 'review:score:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 927, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__SYSTEM_INDUSTRY_READ', 'OPS_SYSTEM', 'system:industry:read', '仅后端', NULL, NULL, 'system:industry:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 928, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__SYSTEM_INDUSTRY_UPDATE', 'OPS_SYSTEM', 'system:industry:update', '仅后端', NULL, NULL, 'system:industry:update', 'IMPLEMENTED', 0, NULL, 'ACTION', 929, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__SYSTEM_PARAM_UPDATE', 'OPS_SYSTEM', 'system:param:update', '仅后端', NULL, NULL, 'system:param:update', 'IMPLEMENTED', 0, NULL, 'ACTION', 930, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__SYSTEM_THEME_READ', 'OPS_SYSTEM', 'system:theme:read', '仅后端', NULL, NULL, 'system:theme:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 931, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__AFTERSALE_REFUND_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__AFTERSALE_REFUND_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__COMMUNITY_REGION_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__COMMUNITY_REGION_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__DASHBOARD_OVERVIEW_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__DASHBOARD_OVERVIEW_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__FINANCE_INVOICE_VERIFY', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__FINANCE_INVOICE_VERIFY' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__FINANCE_PAYOUT_EXECUTE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__FINANCE_PAYOUT_EXECUTE' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__FINANCE_RATE_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__FINANCE_RATE_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__FINANCE_RECON_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__FINANCE_RECON_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__FINANCE_RECON_RESOLVE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__FINANCE_RECON_RESOLVE' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__GROUP_CAMPAIGN_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__GROUP_CAMPAIGN_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__MARKETING_CAMPAIGN_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__MARKETING_CAMPAIGN_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__MARKETING_COUPON_UPDATE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__MARKETING_COUPON_UPDATE' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__MERCHANT_ADMISSION_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__MERCHANT_ADMISSION_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__MERCHANT_ADMISSION_UPDATE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__MERCHANT_ADMISSION_UPDATE' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__MERCHANT_CATEGORY_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__MERCHANT_CATEGORY_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__MERCHANT_MODE_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__MERCHANT_MODE_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__MERCHANT_MODE_UPDATE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__MERCHANT_MODE_UPDATE' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__REVIEW_REVIEW_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__REVIEW_REVIEW_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__REVIEW_SCORE_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__REVIEW_SCORE_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__SYSTEM_INDUSTRY_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__SYSTEM_INDUSTRY_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__SYSTEM_INDUSTRY_UPDATE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__SYSTEM_INDUSTRY_UPDATE' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__SYSTEM_PARAM_UPDATE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__SYSTEM_PARAM_UPDATE' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__SYSTEM_THEME_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__SYSTEM_THEME_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'BD', 'ACT__AFTERSALE_REFUND_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='ACT__AFTERSALE_REFUND_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'BD', 'ACT__DASHBOARD_OVERVIEW_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='ACT__DASHBOARD_OVERVIEW_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'BD', 'ACT__MERCHANT_CATEGORY_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='ACT__MERCHANT_CATEGORY_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'BD', 'ACT__MERCHANT_MODE_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='ACT__MERCHANT_MODE_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'BD', 'ACT__MERCHANT_MODE_UPDATE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='ACT__MERCHANT_MODE_UPDATE' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'GOODS_OPS', 'ACT__AFTERSALE_REFUND_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='GOODS_OPS' AND x.point_code='ACT__AFTERSALE_REFUND_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'GOODS_OPS', 'ACT__DASHBOARD_OVERVIEW_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='GOODS_OPS' AND x.point_code='ACT__DASHBOARD_OVERVIEW_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'GOODS_OPS', 'ACT__GROUP_CAMPAIGN_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='GOODS_OPS' AND x.point_code='ACT__GROUP_CAMPAIGN_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'GOODS_OPS', 'ACT__MARKETING_CAMPAIGN_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='GOODS_OPS' AND x.point_code='ACT__MARKETING_CAMPAIGN_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'GOODS_OPS', 'ACT__MARKETING_COUPON_UPDATE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='GOODS_OPS' AND x.point_code='ACT__MARKETING_COUPON_UPDATE' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPPORT', 'ACT__AFTERSALE_REFUND_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='ACT__AFTERSALE_REFUND_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPPORT', 'ACT__DASHBOARD_OVERVIEW_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='ACT__DASHBOARD_OVERVIEW_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPPORT', 'ACT__REVIEW_REVIEW_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='ACT__REVIEW_REVIEW_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPPORT', 'ACT__REVIEW_SCORE_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='ACT__REVIEW_SCORE_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'CAMPAIGN_OPS', 'ACT__AFTERSALE_REFUND_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='ACT__AFTERSALE_REFUND_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'CAMPAIGN_OPS', 'ACT__DASHBOARD_OVERVIEW_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='ACT__DASHBOARD_OVERVIEW_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'CAMPAIGN_OPS', 'ACT__GROUP_CAMPAIGN_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='ACT__GROUP_CAMPAIGN_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'CAMPAIGN_OPS', 'ACT__MARKETING_CAMPAIGN_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='ACT__MARKETING_CAMPAIGN_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'CAMPAIGN_OPS', 'ACT__MARKETING_COUPON_UPDATE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='ACT__MARKETING_COUPON_UPDATE' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'COMMUNITY_OPS', 'ACT__AFTERSALE_REFUND_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='ACT__AFTERSALE_REFUND_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'COMMUNITY_OPS', 'ACT__COMMUNITY_REGION_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='ACT__COMMUNITY_REGION_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'COMMUNITY_OPS', 'ACT__DASHBOARD_OVERVIEW_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='ACT__DASHBOARD_OVERVIEW_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'COMMUNITY_OPS', 'ACT__SYSTEM_INDUSTRY_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='ACT__SYSTEM_INDUSTRY_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'COMMUNITY_OPS', 'ACT__SYSTEM_INDUSTRY_UPDATE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='ACT__SYSTEM_INDUSTRY_UPDATE' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'AUDITOR', 'ACT__REVIEW_REVIEW_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='AUDITOR' AND x.point_code='ACT__REVIEW_REVIEW_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'AUDITOR', 'ACT__REVIEW_SCORE_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='AUDITOR' AND x.point_code='ACT__REVIEW_SCORE_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'FINANCE', 'ACT__AFTERSALE_REFUND_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='FINANCE' AND x.point_code='ACT__AFTERSALE_REFUND_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'FINANCE', 'ACT__DASHBOARD_OVERVIEW_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='FINANCE' AND x.point_code='ACT__DASHBOARD_OVERVIEW_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'FINANCE', 'ACT__FINANCE_INVOICE_VERIFY', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='FINANCE' AND x.point_code='ACT__FINANCE_INVOICE_VERIFY' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'FINANCE', 'ACT__FINANCE_PAYOUT_EXECUTE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='FINANCE' AND x.point_code='ACT__FINANCE_PAYOUT_EXECUTE' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'FINANCE', 'ACT__FINANCE_RATE_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='FINANCE' AND x.point_code='ACT__FINANCE_RATE_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'FINANCE', 'ACT__FINANCE_RECON_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='FINANCE' AND x.point_code='ACT__FINANCE_RECON_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'FINANCE', 'ACT__FINANCE_RECON_RESOLVE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='FINANCE' AND x.point_code='ACT__FINANCE_RECON_RESOLVE' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'FINANCE', 'ACT__MERCHANT_ADMISSION_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='FINANCE' AND x.point_code='ACT__MERCHANT_ADMISSION_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'FINANCE', 'ACT__MERCHANT_ADMISSION_UPDATE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='FINANCE' AND x.point_code='ACT__MERCHANT_ADMISSION_UPDATE' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'RISK', 'ACT__AFTERSALE_REFUND_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='RISK' AND x.point_code='ACT__AFTERSALE_REFUND_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'RISK', 'ACT__DASHBOARD_OVERVIEW_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='RISK' AND x.point_code='ACT__DASHBOARD_OVERVIEW_READ' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'TECH_OPS', 'ACT__SYSTEM_PARAM_UPDATE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='TECH_OPS' AND x.point_code='ACT__SYSTEM_PARAM_UPDATE' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'TECH_OPS', 'ACT__SYSTEM_THEME_READ', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='TECH_OPS' AND x.point_code='ACT__SYSTEM_THEME_READ' AND x.end_code='OPS');
UPDATE sys_function_point
   SET ui_perm_code = 'merchant:admission:read',
       perm_code    = 'merchant:admission:read'
 WHERE point_code = 'OPS_MERCHANT__TAB_ADMISSION';
DELETE FROM sys_role_point WHERE point_code = 'OPS_MERCHANT__TAB_ADMISSION';
DELETE FROM sys_role_point   WHERE point_code = 'ACT__MERCHANT_ADMISSION_READ';
DELETE FROM sys_function_point WHERE point_code = 'ACT__MERCHANT_ADMISSION_READ';
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MERCHANT__TAB_ADMISSION', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x
                    WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MERCHANT__TAB_ADMISSION');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'FINANCE', 'OPS_MERCHANT__TAB_ADMISSION', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x
                    WHERE x.role_code='FINANCE' AND x.point_code='OPS_MERCHANT__TAB_ADMISSION');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_IAM__TAB_MENU', 'OPS_IAM', '菜单顺序', '账号', '/iam?tab=menu', 'iam:role:grant', 'iam:role:grant', 'IMPLEMENTED', 1, 'P-1.1', 'MENU', 30, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_IAM__TAB_MENU');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_IAM__TAB_MENU', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_IAM__TAB_MENU');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT__TAB_MODE_RISK', 'OPS_MERCHANT', '无照自营风险', '入驻与资质', '/merchants?tab=mode-risk', 'merchant:mode:read', 'merchant:mode:read', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 50, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT__TAB_MODE_RISK', 'OPS', NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT__TAB_MODE_RISK', 'OPS', NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_MERCHANT__TAB_QUALIFICATIONS', 'OPS_MERCHANT', '资质档案', '入驻与资质', '/merchants?tab=qualifications', 'merchant:category:read', 'merchant:category:read', 'IMPLEMENTED', 0, 'P-11.1', 'MENU', 40, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT__TAB_QUALIFICATIONS', 'OPS', NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT__TAB_QUALIFICATIONS', 'OPS', NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
UPDATE rvw_appeal SET status = 'REJECTED' WHERE status = 'DISMISSED';
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_FINANCE__TAB_POINTS', 'OPS_FINANCE', '积分资金看板', '分账结算', '/finance?tab=points', 'finance:settle:read', 'finance:settle:read', 'IMPLEMENTED', 1, 'P-12.1', 'MENU', 50, NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE__TAB_POINTS', 'OPS', NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE__TAB_POINTS', 'OPS', NOW(), NOW()) ON DUPLICATE KEY UPDATE point_code = point_code;
UPDATE mch_entity       SET legal_form = 'NATURAL_PERSON' WHERE legal_form = 'MICRO';
UPDATE mch_entity_apply SET legal_form = 'NATURAL_PERSON' WHERE legal_form = 'MICRO';
UPDATE sys_legal_form
   SET legal_form = 'NATURAL_PERSON',
       name = '自然人',
       remark = '无营业执照的自然人经营者。**与法规「小微企业」无关** —— 那是有照企业的规模划型，重名且含义相反。受行业白名单限制（线上业态不收）；支付宝侧无对应档，故 alipay_code 留空'
 WHERE legal_form = 'MICRO';
UPDATE mch_admission_policy SET legal_form = 'NATURAL_PERSON' WHERE legal_form = 'MICRO';
UPDATE mch_entity SET biz_qualification = 'REGISTERED'
 WHERE legal_form IN ('INDIVIDUAL', 'ENTERPRISE');
UPDATE mch_entity SET biz_qualification = 'EXEMPT', exempt_type = 'PETTY'
 WHERE legal_form = 'NATURAL_PERSON';
UPDATE mch_entity SET is_agri_producer = 1, exempt_type = 'AGRI'
 WHERE legal_form = 'NATURAL_PERSON'
 ORDER BY entity_no LIMIT 2;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MESSAGE__TAB_NOTIFYLOG', 'OPS_MESSAGE', '发送记录', '触达', '/messages?tab=notifyLog', 'message:template:read', 'message:template:read', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 20, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MESSAGE__TAB_NOTIFYLOG');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MESSAGE__TAB_NOTIFYLOG', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MESSAGE__TAB_NOTIFYLOG');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPPORT', 'OPS_MESSAGE__TAB_NOTIFYLOG', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='OPS_MESSAGE__TAB_NOTIFYLOG');
UPDATE sys_legal_form SET wechat_code = 'INDIVIDUAL' WHERE legal_form = 'INDIVIDUAL';
UPDATE sys_legal_form SET settle_account_type = 'PERSONAL_BANK_CARD'
 WHERE settle_account_type = 'PERSONAL_OPENID';
UPDATE sys_legal_form
   SET remark = CONCAT(remark, ' ⚠️ 结算到【个人银行卡】（不是微信零钱）。走【小微商户进件】接口，与个体户/企业的【特约商户进件】不是同一个接口，subject_type 只能是 SUBJECT_TYPE_MICRO')
 WHERE legal_form = 'NATURAL_PERSON' AND remark NOT LIKE '%小微商户进件%';
UPDATE sys_legal_form
   SET remark = CONCAT(remark, '。走【特约商户进件】，subject_type=SUBJECT_TYPE_INDIVIDUAL')
 WHERE legal_form = 'INDIVIDUAL' AND remark NOT LIKE '%特约商户进件%';
UPDATE sys_legal_form
   SET remark = CONCAT(remark, '。走【特约商户进件】，subject_type=SUBJECT_TYPE_ENTERPRISE')
 WHERE legal_form = 'ENTERPRISE' AND remark NOT LIKE '%特约商户进件%';
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MERCHANT__TAB_STORES', 'OPS_MERCHANT', '门店档案', '入驻与资质', '/merchants?tab=stores', 'merchant:merchant:read', 'merchant:merchant:read', 'IMPLEMENTED', 0, 'P-11.2', 'MENU', 25, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MERCHANT__TAB_STORES');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MERCHANT__TAB_STORES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MERCHANT__TAB_STORES');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'BD', 'OPS_MERCHANT__TAB_STORES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='OPS_MERCHANT__TAB_STORES');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_PRODUCT__TAB_TEMPLATES', 'OPS_PRODUCT', '规格模板维护', '规格模板', '/products?tab=templates', 'product:category:read', 'product:category:read', 'IMPLEMENTED', 1, 'P-3.4', 'MENU', 50, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_PRODUCT__TAB_TEMPLATES');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_PRODUCT__TAB_TEMPLATES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_PRODUCT__TAB_TEMPLATES');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'GOODS_OPS', 'OPS_PRODUCT__TAB_TEMPLATES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='GOODS_OPS' AND x.point_code='OPS_PRODUCT__TAB_TEMPLATES');
UPDATE sys_function_point
   SET perm_code = 'finance:withdraw:approve', backend_status = 'IMPLEMENTED', updated_at = NOW()
 WHERE point_code = 'OPS_FINANCE__TAB_WITHDRAW';
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'FINANCE', 'OPS_FINANCE__TAB_WITHDRAW', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x
                    WHERE x.role_code = 'FINANCE'
                      AND x.point_code = 'OPS_FINANCE__TAB_WITHDRAW');
UPDATE ful_batch SET status = 'PLANNED' WHERE status = 'PENDING';
UPDATE ful_batch SET status = 'SIGNED' WHERE status = 'RECEIVED';
INSERT INTO ful_freight_template
(template_no, name, first_weight_gram, first_fee, add_weight_gram, add_fee, free_threshold, is_default, out_of_range, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('FT0001', '默认运费模板', 1000, 800, 500, 200, 9900, 1,
 '[{"region":"新疆维吾尔自治区","action":"SURCHARGE","surcharge":2000},{"region":"西藏自治区","action":"REJECT","surcharge":0}]',
 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO ful_carrier
(carrier, name, enabled, priority, account_masked, api_key_configured, pickup_cutoff, sla_hours, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('SF', '顺丰速运', 1, 1, 'SF-****-8821', 1, '17:00', 48, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO ful_carrier
(carrier, name, enabled, priority, account_masked, api_key_configured, pickup_cutoff, sla_hours, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('JD', '京东物流', 1, 2, 'JD-****-3390', 1, '16:30', 72, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO ful_carrier
(carrier, name, enabled, priority, account_masked, api_key_configured, pickup_cutoff, sla_hours, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('YTO', '圆通速递', 0, 3, NULL, 0, '18:00', 96, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
UPDATE sys_function_point SET perm_code = 'fulfillment:batch:read', backend_status = 'IMPLEMENTED', ui_ready = 1
 WHERE point_code = 'OPS_FULFILLMENT';
UPDATE sys_function_point SET perm_code = 'fulfillment:batch:read', backend_status = 'IMPLEMENTED', ui_ready = 1
 WHERE point_code = 'OPS_FULFILLMENT__TAB_SORTING';
UPDATE sys_function_point SET perm_code = 'fulfillment:redeem:read', backend_status = 'IMPLEMENTED', ui_ready = 1
 WHERE point_code = 'OPS_FULFILLMENT__TAB_REDEEM';
UPDATE sys_function_point SET perm_code = 'fulfillment:rule:update', backend_status = 'IMPLEMENTED', ui_ready = 1
 WHERE point_code = 'OPS_FULFILLMENT__TAB_OVERDUE';
UPDATE sys_function_point SET perm_code = 'fulfillment:logistics:read', backend_status = 'IMPLEMENTED', ui_ready = 1
 WHERE point_code = 'OPS_FULFILLMENT__TAB_EXPRESS';
UPDATE sys_function_point SET perm_code = 'fulfillment:rule:update', backend_status = 'IMPLEMENTED', ui_ready = 1
 WHERE point_code = 'OPS_FULFILLMENT__TAB_FREIGHT';
UPDATE sys_function_point SET perm_code = 'fulfillment:logistics:read', backend_status = 'IMPLEMENTED', ui_ready = 1
 WHERE point_code = 'OPS_FULFILLMENT__TAB_CARRIER';
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'OPS_FULFILLMENT', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='OPS_FULFILLMENT');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'OPS_FULFILLMENT__TAB_SORTING', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='OPS_FULFILLMENT__TAB_SORTING');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'OPS_FULFILLMENT__TAB_REDEEM', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='OPS_FULFILLMENT__TAB_REDEEM');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'OPS_FULFILLMENT__TAB_OVERDUE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='OPS_FULFILLMENT__TAB_OVERDUE');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'OPS_FULFILLMENT__TAB_EXPRESS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='OPS_FULFILLMENT__TAB_EXPRESS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'OPS_FULFILLMENT__TAB_FREIGHT', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='OPS_FULFILLMENT__TAB_FREIGHT');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'OPS_FULFILLMENT__TAB_CARRIER', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='OPS_FULFILLMENT__TAB_CARRIER');
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at)
SELECT 'OPS_GROWTH', '增长与归因', 'OPS', 'TrendingUp', '/growth', 100, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function x WHERE x.function_code='OPS_GROWTH');
INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at)
SELECT 'OPS_RISK', '风控', 'OPS', 'ShieldAlert', '/risk', 160, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function x WHERE x.function_code='OPS_RISK');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_GROWTH', 'OPS_GROWTH', '归因规则', '归因引擎', '/growth', 'growth:attribution:read', 'growth:attribution:read', 'IMPLEMENTED', 1, 'P-9.1', 'MENU', 10, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_GROWTH');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_GROWTH__TAB_TRACES', 'OPS_GROWTH', '归因链路审计', '归因引擎', '/growth?tab=traces', 'growth:attribution:read', 'growth:attribution:read', 'IMPLEMENTED', 1, 'P-9.1', 'MENU', 20, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_GROWTH__TAB_TRACES');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_GROWTH__TAB_FISSION', 'OPS_GROWTH', '邀请有礼配置', '裂变活动', '/growth?tab=fission', 'growth:fission:update', 'growth:fission:update', 'IMPLEMENTED', 1, 'P-9.2', 'MENU', 30, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_GROWTH__TAB_FISSION');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_RISK', 'OPS_RISK', '风险事件', '识别', '/risk', 'risk:rule:read', 'risk:rule:read', 'IMPLEMENTED', 1, 'P-16.2', 'MENU', 10, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_RISK');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_RISK__TAB_BLACKLIST', 'OPS_RISK', '黑名单与申诉', '处置', '/risk?tab=blacklist', 'risk:blacklist:update', 'risk:blacklist:update', 'IMPLEMENTED', 1, 'P-16.2', 'MENU', 20, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_RISK__TAB_BLACKLIST');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_RISK__TAB_RULES', 'OPS_RISK', '拦截规则配置', '处置', '/risk?tab=rules', 'risk:rule:update', 'risk:rule:update', 'IMPLEMENTED', 1, 'P-16.2', 'MENU', 30, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_RISK__TAB_RULES');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__GROWTH_ATTRIBUTION_UPDATE', 'OPS_GROWTH', 'growth:attribution:update', '仅后端', NULL, NULL, 'growth:attribution:update', 'IMPLEMENTED', 0, NULL, 'ACTION', 920, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__GROWTH_ATTRIBUTION_UPDATE');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__GROWTH_FISSION_READ', 'OPS_GROWTH', 'growth:fission:read', '仅后端', NULL, NULL, 'growth:fission:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 921, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__GROWTH_FISSION_READ');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__RISK_BLACKLIST_READ', 'OPS_RISK', 'risk:blacklist:read', '仅后端', NULL, NULL, 'risk:blacklist:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 927, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__RISK_BLACKLIST_READ');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__RISK_EVENT_HANDLE', 'OPS_RISK', 'risk:event:handle', '仅后端', NULL, NULL, 'risk:event:handle', 'IMPLEMENTED', 0, NULL, 'ACTION', 928, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__RISK_EVENT_HANDLE');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__RISK_EVENT_READ', 'OPS_RISK', 'risk:event:read', '仅后端', NULL, NULL, 'risk:event:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 929, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__RISK_EVENT_READ');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_GROWTH', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_GROWTH');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_GROWTH__TAB_TRACES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_GROWTH__TAB_TRACES');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_GROWTH__TAB_FISSION', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_GROWTH__TAB_FISSION');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_RISK', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_RISK');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_RISK__TAB_BLACKLIST', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_RISK__TAB_BLACKLIST');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_RISK__TAB_RULES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_RISK__TAB_RULES');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__GROWTH_ATTRIBUTION_UPDATE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__GROWTH_ATTRIBUTION_UPDATE');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__GROWTH_FISSION_READ', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__GROWTH_FISSION_READ');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__RISK_BLACKLIST_READ', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__RISK_BLACKLIST_READ');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__RISK_EVENT_HANDLE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__RISK_EVENT_HANDLE');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__RISK_EVENT_READ', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__RISK_EVENT_READ');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'BD', 'OPS_GROWTH', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='OPS_GROWTH');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'BD', 'OPS_GROWTH__TAB_TRACES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='OPS_GROWTH__TAB_TRACES');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'CAMPAIGN_OPS', 'OPS_GROWTH', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='OPS_GROWTH');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'CAMPAIGN_OPS', 'OPS_GROWTH__TAB_TRACES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='OPS_GROWTH__TAB_TRACES');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'CAMPAIGN_OPS', 'OPS_GROWTH__TAB_FISSION', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='OPS_GROWTH__TAB_FISSION');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'CAMPAIGN_OPS', 'ACT__GROWTH_ATTRIBUTION_UPDATE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='ACT__GROWTH_ATTRIBUTION_UPDATE');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'CAMPAIGN_OPS', 'ACT__GROWTH_FISSION_READ', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='CAMPAIGN_OPS' AND x.point_code='ACT__GROWTH_FISSION_READ');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'RISK', 'OPS_RISK', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='RISK' AND x.point_code='OPS_RISK');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'RISK', 'OPS_RISK__TAB_BLACKLIST', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='RISK' AND x.point_code='OPS_RISK__TAB_BLACKLIST');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'RISK', 'OPS_RISK__TAB_RULES', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='RISK' AND x.point_code='OPS_RISK__TAB_RULES');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'RISK', 'ACT__RISK_BLACKLIST_READ', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='RISK' AND x.point_code='ACT__RISK_BLACKLIST_READ');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'RISK', 'ACT__RISK_EVENT_HANDLE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='RISK' AND x.point_code='ACT__RISK_EVENT_HANDLE');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'RISK', 'ACT__RISK_EVENT_READ', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='RISK' AND x.point_code='ACT__RISK_EVENT_READ');
UPDATE sys_function_point SET name = '通道总览', updated_at = NOW()
 WHERE point_code = 'OPS_MESSAGE' AND name = '消息模板与推送';
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MESSAGE__TAB_SMS', 'OPS_MESSAGE', '短信', '触达', '/messages?tab=sms', 'message:template:read', 'message:template:read', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 21, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MESSAGE__TAB_SMS');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MESSAGE__TAB_MAIL', 'OPS_MESSAGE', '邮件', '触达', '/messages?tab=mail', 'message:template:read', 'message:template:read', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 22, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MESSAGE__TAB_MAIL');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MESSAGE__TAB_WXSUB', 'OPS_MESSAGE', '微信订阅消息', '触达', '/messages?tab=wxsub', 'message:template:read', 'message:template:read', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 23, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MESSAGE__TAB_WXSUB');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MESSAGE__TAB_APPPUSH', 'OPS_MESSAGE', 'App 推送', '触达', '/messages?tab=apppush', 'message:template:read', 'message:template:read', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 24, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MESSAGE__TAB_APPPUSH');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MESSAGE__TAB_INAPP', 'OPS_MESSAGE', '站内信模板与推送任务', '触达', '/messages?tab=inapp', 'message:template:read', 'message:template:read', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 25, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MESSAGE__TAB_INAPP');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MESSAGE__TAB_SMS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MESSAGE__TAB_SMS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MESSAGE__TAB_MAIL', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MESSAGE__TAB_MAIL');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MESSAGE__TAB_WXSUB', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MESSAGE__TAB_WXSUB');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MESSAGE__TAB_APPPUSH', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MESSAGE__TAB_APPPUSH');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MESSAGE__TAB_INAPP', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MESSAGE__TAB_INAPP');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPPORT', 'OPS_MESSAGE__TAB_SMS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='OPS_MESSAGE__TAB_SMS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPPORT', 'OPS_MESSAGE__TAB_MAIL', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='OPS_MESSAGE__TAB_MAIL');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPPORT', 'OPS_MESSAGE__TAB_WXSUB', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='OPS_MESSAGE__TAB_WXSUB');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPPORT', 'OPS_MESSAGE__TAB_APPPUSH', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='OPS_MESSAGE__TAB_APPPUSH');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPPORT', 'OPS_MESSAGE__TAB_INAPP', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='OPS_MESSAGE__TAB_INAPP');
INSERT INTO notify_template (template_no, name, channel, content, provider_template_id, enabled, created_at, updated_at)
SELECT 'TPL_SMS_OTP', '验证码', 'SMS',
       '【数智邻购】您的验证码是 {code}，5 分钟内有效，请勿泄露。',
       'SMS_474945291', 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM notify_template x WHERE x.template_no='TPL_SMS_OTP');
INSERT INTO notify_template (template_no, name, channel, content, provider_template_id, enabled, created_at, updated_at)
SELECT 'TPL_MAIL_TEST', '通道联通测试', 'MAIL',
       '{subject}\n\n{body}',
       NULL, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM notify_template x WHERE x.template_no='TPL_MAIL_TEST');
INSERT INTO notify_template (template_no, name, channel, content, provider_template_id, enabled, created_at, updated_at)
SELECT 'TPL_WX_ARRIVED', '到货通知', 'WXSUB',
       '您有 {number1} 件包裹已到自提点 · {thing2}',
       NULL, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM notify_template x WHERE x.template_no='TPL_WX_ARRIVED');
INSERT INTO notify_template (template_no, name, channel, content, provider_template_id, enabled, created_at, updated_at)
SELECT 'TPL_WX_REFUNDED', '退款通知', 'WXSUB',
       '退款 {amount1} 已处理 · {thing2}',
       NULL, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM notify_template x WHERE x.template_no='TPL_WX_REFUNDED');
INSERT INTO notify_template (template_no, name, channel, content, provider_template_id, enabled, created_at, updated_at)
SELECT 'TPL_PUSH_TEST', '通用推送', 'PUSH',
       '{subject}\n{body}',
       NULL, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM notify_template x WHERE x.template_no='TPL_PUSH_TEST');
INSERT INTO notify_template (template_no, name, channel, content, provider_template_id, enabled, created_at, updated_at)
SELECT 'TPL_INAPP_TEST', '站内信', 'INAPP',
       '{subject}\n{body}',
       NULL, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM notify_template x WHERE x.template_no='TPL_INAPP_TEST');
INSERT INTO notify_template (template_no, name, channel, content, provider_template_id, enabled, created_at, updated_at)
SELECT 'TPL_MAIL_OPS_INIT_PWD', '运营账号开通', 'MAIL',
       '你好 {realName}，

你的运营端账号已开通。
登录名：{username}
初始密码：{password}

首次登录会要求你立即修改密码。请勿转发本邮件。
',
       NULL, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM notify_template x WHERE x.template_no='TPL_MAIL_OPS_INIT_PWD');
INSERT INTO notify_template (template_no, name, channel, content, provider_template_id, enabled, created_at, updated_at)
SELECT 'TPL_MAIL_OPS_RESET_PWD', '运营密码重置', 'MAIL',
       '你好 {realName}，

有人为你的运营端账号申请了密码重置。
重置码（{ttlMinutes} 分钟内有效，只能用一次）：

    {token}

如果不是你本人操作，忽略本邮件即可，你的密码不会有任何变化。
',
       NULL, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM notify_template x WHERE x.template_no='TPL_MAIL_OPS_RESET_PWD');
UPDATE notify_template SET content = '{subject}

{body}' WHERE template_no = 'TPL_MAIL_TEST';
UPDATE notify_template SET content = '{subject}
{body}' WHERE template_no = 'TPL_PUSH_TEST';
UPDATE notify_template SET content = '{subject}
{body}' WHERE template_no = 'TPL_INAPP_TEST';
UPDATE sys_function_point
   SET name = '站内信模板', updated_at = NOW()
 WHERE point_code = 'OPS_MESSAGE__TAB_INAPP';
INSERT INTO notify_template (template_no, name, channel, lang, content, provider_template_id, enabled, created_at, updated_at)
SELECT 'TPL_MAIL_OPS_RESET_PWD', 'Ops password reset', 'MAIL', 'en',
       'Hi {realName},

Someone requested a password reset for your operations account.
Reset code (valid for {ttlMinutes} minutes, single use):

    {token}

If this was not you, just ignore this email — your password stays unchanged.
',
       NULL, 1, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM notify_template x
                    WHERE x.template_no='TPL_MAIL_OPS_RESET_PWD' AND x.lang='en');
INSERT INTO sys_merchant_plan_def
    (plan_code, name, store_quota, staff_quota, cross_store_stats, trial_days, enabled, sort,
     tenant_no, created_at, updated_at, version, deleted)
SELECT 'FREE', '孵化版', 1, 0, 0, 0, 1, 10, 'MAIN', NOW(), NOW(), 0, 0 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_merchant_plan_def x WHERE x.plan_code = 'FREE');
INSERT INTO sys_merchant_plan_def
    (plan_code, name, store_quota, staff_quota, cross_store_stats, trial_days, enabled, sort,
     tenant_no, created_at, updated_at, version, deleted)
SELECT 'PRO', '成长版', 3, 3, 1, 14, 1, 20, 'MAIN', NOW(), NOW(), 0, 0 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_merchant_plan_def x WHERE x.plan_code = 'PRO');
INSERT INTO sys_merchant_plan_def
    (plan_code, name, store_quota, staff_quota, cross_store_stats, trial_days, enabled, sort,
     tenant_no, created_at, updated_at, version, deleted)
SELECT 'CHAIN', '连锁版', 10, 15, 1, 14, 1, 30, 'MAIN', NOW(), NOW(), 0, 0 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_merchant_plan_def x WHERE x.plan_code = 'CHAIN');
DELETE FROM sys_role_point
 WHERE point_code IN ('OPS_GROWTH_01', 'OPS_GROWTH_02', 'OPS_GROWTH_03',
                      'OPS_RISK_01', 'OPS_RISK_02', 'OPS_RISK_03');
DELETE FROM sys_function_point
 WHERE point_code IN ('OPS_GROWTH_01', 'OPS_GROWTH_02', 'OPS_GROWTH_03',
                      'OPS_RISK_01', 'OPS_RISK_02', 'OPS_RISK_03');
UPDATE sys_function_point
   SET perm_code = ui_perm_code, backend_status = 'IMPLEMENTED', updated_at = NOW()
 WHERE point_code IN ('OPS_GROWTH', 'OPS_GROWTH__TAB_TRACES', 'OPS_GROWTH__TAB_FISSION',
                      'OPS_RISK', 'OPS_RISK__TAB_BLACKLIST', 'OPS_RISK__TAB_RULES')
   AND (perm_code IS NULL OR perm_code = '')
   AND ui_perm_code IS NOT NULL;
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MERCHANT__TAB_PLANS', 'OPS_MERCHANT', '增值包与额度', '增值包', '/merchants?tab=plans', 'merchant:merchant:read', 'merchant:merchant:read', 'IMPLEMENTED', 0, 'P-11.2', 'MENU', 80, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MERCHANT__TAB_PLANS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MERCHANT__TAB_PLANS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MERCHANT__TAB_PLANS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'BD', 'OPS_MERCHANT__TAB_PLANS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='OPS_MERCHANT__TAB_PLANS');
INSERT INTO sys_function_point
    (point_code, function_code, name, group_name, href, ui_perm_code, perm_code,
     backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
VALUES ('OPS_SYSTEM__TAB_STORAGE', 'OPS_SYSTEM', '存储空间治理', '运行配置', '/system?tab=storage',
        'system:media:read', 'system:media:read', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 31, NOW(), NOW());
INSERT INTO sys_function_point
    (point_code, function_code, name, group_name, href, ui_perm_code, perm_code,
     backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
VALUES ('ACT__SYSTEM_MEDIA_PURGE', 'OPS_SYSTEM', 'system:media:purge', '页面内操作', NULL,
        'system:media:purge', 'system:media:purge', 'IMPLEMENTED', 1, NULL, 'ACTION', 910, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES ('SUPER_ADMIN', 'OPS_SYSTEM__TAB_STORAGE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES ('SUPER_ADMIN', 'ACT__SYSTEM_MEDIA_PURGE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES ('TECH_OPS', 'OPS_SYSTEM__TAB_STORAGE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES ('TECH_OPS', 'ACT__SYSTEM_MEDIA_PURGE', 'OPS', NOW(), NOW());
INSERT INTO notify_scene_channel (scene_code, audience, channel, enabled, push_level, created_at, updated_at)
SELECT t.scene_code, t.audience, t.channel, t.enabled, t.push_level, NOW(), NOW()
FROM (
    
    SELECT 'ORDER_PAID' AS scene_code, 'C_USER' AS audience, 'INAPP' AS channel, 1 AS enabled, 'NORMAL' AS push_level UNION ALL
    SELECT 'ORDER_PAID', 'C_USER', 'PUSH', 0, 'NORMAL' UNION ALL
    SELECT 'ORDER_ARRIVED', 'C_USER', 'INAPP', 1, 'NORMAL' UNION ALL
    SELECT 'ORDER_ARRIVED', 'C_USER', 'WXSUB', 1, 'NORMAL' UNION ALL
    SELECT 'ORDER_ARRIVED', 'C_USER', 'PUSH', 1, 'NORMAL' UNION ALL
    SELECT 'SUB_ORDER_COMPLETED', 'C_USER', 'INAPP', 1, 'NORMAL' UNION ALL
    SELECT 'SUB_ORDER_COMPLETED', 'C_USER', 'PUSH', 0, 'NORMAL' UNION ALL
    SELECT 'AFTER_SALE_REFUNDED', 'C_USER', 'INAPP', 1, 'NORMAL' UNION ALL
    SELECT 'AFTER_SALE_REFUNDED', 'C_USER', 'WXSUB', 1, 'NORMAL' UNION ALL
    SELECT 'AFTER_SALE_REFUNDED', 'C_USER', 'PUSH', 0, 'NORMAL' UNION ALL
    
    SELECT 'SUB_ORDER_PAID', 'B_STAFF', 'INAPP', 1, 'NORMAL' UNION ALL
    SELECT 'SUB_ORDER_PAID', 'B_STAFF', 'PUSH', 1, 'RING' UNION ALL
    SELECT 'AFTER_SALE_APPLIED', 'B_STAFF', 'INAPP', 1, 'NORMAL' UNION ALL
    SELECT 'AFTER_SALE_APPLIED', 'B_STAFF', 'PUSH', 1, 'NORMAL' UNION ALL
    SELECT 'REVIEW_CREATED', 'B_STAFF', 'INAPP', 1, 'NORMAL' UNION ALL
    SELECT 'REVIEW_CREATED', 'B_STAFF', 'PUSH', 1, 'NORMAL'
) t
WHERE NOT EXISTS (
    SELECT 1 FROM notify_scene_channel m
    WHERE m.scene_code = t.scene_code AND m.audience = t.audience AND m.channel = t.channel
);
INSERT INTO notify_channel (channel_no, channel_type, provider, scope, cred_ref, created_at, updated_at)
SELECT t.channel_no, t.channel_type, t.provider, t.scope, t.cred_ref, NOW(), NOW()
FROM (
    SELECT 'NCH-SMS-ALI' AS channel_no, 'SMS' AS channel_type, 'ALI' AS provider, 'PLATFORM' AS scope, 'shop.sms.ali' AS cred_ref UNION ALL
    SELECT 'NCH-MAIL-SMTP', 'MAIL', 'SMTP', 'PLATFORM', 'shop.mail' UNION ALL
    SELECT 'NCH-WXSUB-WECHAT', 'WXSUB', 'WECHAT', 'PLATFORM', 'shop.wx' UNION ALL
    SELECT 'NCH-PUSH-GETUI', 'PUSH', 'GETUI', 'PLATFORM', 'shop.push.getui' UNION ALL
    SELECT 'NCH-PUSH-FCM', 'PUSH', 'FCM', 'PLATFORM', 'shop.push.fcm' UNION ALL
    SELECT 'NCH-PUSH-APNS', 'PUSH', 'APNS', 'PLATFORM', 'shop.push.apns' UNION ALL
    SELECT 'NCH-INAPP', 'INAPP', 'INTERNAL', 'PLATFORM', NULL UNION ALL
    SELECT 'NCH-SMS-ALI-TEST', 'SMS', 'ALI', 'TEST', NULL UNION ALL
    SELECT 'NCH-MAIL-SMTP-TEST', 'MAIL', 'SMTP', 'TEST', NULL UNION ALL
    SELECT 'NCH-WXSUB-WECHAT-TEST', 'WXSUB', 'WECHAT', 'TEST', NULL UNION ALL
    SELECT 'NCH-PUSH-GETUI-TEST', 'PUSH', 'GETUI', 'TEST', NULL
) t
WHERE NOT EXISTS (
    SELECT 1 FROM notify_channel m
    WHERE m.channel_type = t.channel_type AND m.provider = t.provider
      AND m.scope = t.scope AND m.owner_no = ''
);
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_MESSAGE__TAB_BROADCAST', 'OPS_MESSAGE', '营销广播', '触达', '/messages?tab=broadcast', 'message:template:read', 'message:template:read', 'IMPLEMENTED', 1, 'P-14.1', 'MENU', 26, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_MESSAGE__TAB_BROADCAST');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_MESSAGE__TAB_BROADCAST', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_MESSAGE__TAB_BROADCAST');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPPORT', 'OPS_MESSAGE__TAB_BROADCAST', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPPORT' AND x.point_code='OPS_MESSAGE__TAB_BROADCAST');
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('CAT500', NULL,     1, '虚拟商品', 'Virtual Goods', NULL, 50, 'VIRTUAL', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT510', 'CAT500', 2, '话费充值', 'Mobile Top-up', NULL, 10, 'VIRTUAL', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT520', 'CAT500', 2, '会员充值', 'Memberships',   NULL, 20, 'VIRTUAL', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
UPDATE prd_goods SET category_no = 'CAT100' WHERE (category_no IS NULL OR category_no = '') AND type = 'FRESH';
UPDATE prd_goods SET category_no = 'CAT300' WHERE (category_no IS NULL OR category_no = '') AND type = 'SERVICE';
UPDATE prd_goods SET category_no = 'CAT400' WHERE (category_no IS NULL OR category_no = '') AND type = 'CARD';
UPDATE prd_goods SET category_no = 'CAT500' WHERE (category_no IS NULL OR category_no = '') AND type = 'VIRTUAL';
UPDATE prd_goods SET category_no = 'CAT200' WHERE category_no IS NULL OR category_no = '';
UPDATE prd_goods SET type = 'FRESH'   WHERE category_no IN ('CAT100','CAT110','CAT120','CAT111','CAT112','CAT121') AND type <> 'FRESH';
UPDATE prd_goods SET type = 'NORMAL'  WHERE category_no IN ('CAT200','CAT210') AND type <> 'NORMAL';
UPDATE prd_goods SET type = 'SERVICE' WHERE category_no IN ('CAT300') AND type <> 'SERVICE';
UPDATE prd_goods SET type = 'CARD'    WHERE category_no IN ('CAT400') AND type <> 'CARD';
UPDATE prd_goods SET type = 'VIRTUAL' WHERE category_no IN ('CAT500','CAT510','CAT520') AND type <> 'VIRTUAL';
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
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_PRODUCT__TAB_SPU_STD', 'OPS_PRODUCT', '标准品库', '标准品', '/products?tab=spu-std', 'product:std:read', 'product:std:read', 'IMPLEMENTED', 1, 'P-3.5', 'MENU', 60, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__PRODUCT_STD_UPDATE', 'OPS_PRODUCT', 'product:std:update', '页面内操作', NULL, 'product:std:update', 'product:std:update', 'IMPLEMENTED', 1, NULL, 'ACTION', 902, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT__TAB_SPU_STD', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__PRODUCT_STD_UPDATE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT__TAB_SPU_STD', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT__PRODUCT_STD_UPDATE', 'OPS', NOW(), NOW());
UPDATE prd_category SET required_code = 'FRESH_VEG',
       qualification_required = '["食品经营许可证"]' WHERE category_no = 'CAT110';
UPDATE prd_category SET required_code = 'FRESH_FRUIT',
       qualification_required = '["食品经营许可证"]' WHERE category_no = 'CAT120';
UPDATE prd_goods   SET category_no = 'CAT110' WHERE category_no IN ('CAT111', 'CAT112');
UPDATE prd_goods   SET category_no = 'CAT120' WHERE category_no = 'CAT121';
UPDATE prd_spu_std SET category_no = 'CAT110' WHERE category_no IN ('CAT111', 'CAT112');
UPDATE prd_spu_std SET category_no = 'CAT120' WHERE category_no = 'CAT121';
UPDATE prd_category SET status = 'ARCHIVED' WHERE level = 3;
INSERT INTO prd_topic
(topic_no, title, subtitle, cover, sort, status, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('TP0001', '早餐必备', '7 点前送到楼下', '', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('TP0002', '本地时令', '当季当地，今天到货', '', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('OPS_PRODUCT__TAB_TOPICS', 'OPS_PRODUCT', '主题分类', '陈列', '/products?tab=topics', 'product:topic:read', 'product:topic:read', 'IMPLEMENTED', 1, 'P-3.6', 'MENU', 70, NOW(), NOW());
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES ('ACT__PRODUCT_TOPIC_UPDATE', 'OPS_PRODUCT', 'product:topic:update', '页面内操作', NULL, 'product:topic:update', 'product:topic:update', 'IMPLEMENTED', 1, NULL, 'ACTION', 903, NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT__TAB_TOPICS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT__PRODUCT_TOPIC_UPDATE', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT__TAB_TOPICS', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT__PRODUCT_TOPIC_UPDATE', 'OPS', NOW(), NOW());
INSERT INTO prd_spec_template
(template_no, scope, category_type, name, options, entity_no, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES

('SPT_SEED_FRESH_WEIGHT', 'PLATFORM', 'FRESH', '重量',
 '[{"code":"W500G","label":"约1斤"},{"code":"W1KG","label":"约2斤"},{"code":"W2500G","label":"约5斤"},{"code":"W5KG","label":"约10斤"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_SEED_FRESH_PACK', 'PLATFORM', 'FRESH', '包装',
 '[{"code":"PBULK","label":"散装"},{"code":"PBAG","label":"袋装"},{"code":"PBOX","label":"盒装"},{"code":"PGIFT","label":"礼盒"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),

('SPT_SEED_NORMAL_COUNT', 'PLATFORM', 'NORMAL', '规格',
 '[{"code":"C1","label":"单件"},{"code":"C2","label":"2件装"},{"code":"C5","label":"5件装"},{"code":"CCASE","label":"整箱"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_SEED_NORMAL_PACK', 'PLATFORM', 'NORMAL', '包装',
 '[{"code":"PBAG","label":"袋装"},{"code":"PBOTTLE","label":"瓶装"},{"code":"PBOX","label":"盒装"},{"code":"PCAN","label":"罐装"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),

('SPT_SEED_SERVICE_DURATION', 'PLATFORM', 'SERVICE', '时长',
 '[{"code":"D30","label":"30分钟"},{"code":"D60","label":"60分钟"},{"code":"D90","label":"90分钟"},{"code":"D120","label":"120分钟"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_SEED_SERVICE_HEADCOUNT', 'PLATFORM', 'SERVICE', '人数',
 '[{"code":"H1","label":"1人"},{"code":"H2","label":"2人"},{"code":"H3","label":"3人及以上"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO sys_auth_code
(code, name, required_qualification, sort, enabled, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('ALCOHOL', '酒类', '食品经营许可证（含酒类）', 27, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('CAT150', 'CAT100', 2, '酒类', 'Alcohol', NULL, 50, 'STANDARD', NULL,
 '["食品经营许可证（含酒类）"]', 'ALCOHOL', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT160', 'CAT100', 2, '茶叶', 'Tea', NULL, 60, 'STANDARD', NULL,
 '["仅销售预包装食品备案"]', 'PACKAGED_FOOD', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('CAT600', NULL,     1, '电子产品', 'Electronics',      NULL, 60, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT610', 'CAT600', 2, '手机数码', 'Phones & Digital', NULL, 10, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT620', 'CAT600', 2, '家用电器', 'Home Appliances',  NULL, 20, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT630', 'CAT600', 2, '配件耗材', 'Accessories',      NULL, 30, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO sys_auth_code
(code, name, required_qualification, sort, enabled, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('FRESH_MEAT',      '肉禽蛋',     '食品经营许可证',           15, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),


('PET_FOOD',        '宠物食品',   '饲料和饲料添加剂经营备案', 70, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),

('INFANT_FORMULA',  '婴幼儿食品', '婴幼儿配方乳粉销售备案',   28, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
UPDATE sys_auth_code SET enabled = 1 WHERE code = 'FRESH_DAIRY' AND enabled = 0;
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('CAT170', 'CAT100', 2, '肉禽蛋', 'Meat & Eggs', NULL, 70, 'FRESH', NULL,
 '["食品经营许可证"]', 'FRESH_MEAT', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT180', 'CAT100', 2, '乳制品', 'Dairy', NULL, 80, 'FRESH', NULL,
 '["食品经营许可证"]', 'FRESH_DAIRY', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('CAT700', NULL,     1, '食品饮料',   'Food & Drinks',     NULL, 70, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT710', 'CAT700', 2, '粮油调味',   'Grain & Seasoning', NULL, 10, 'STANDARD', NULL,
 '["仅销售预包装食品备案"]', 'PACKAGED_FOOD', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT720', 'CAT700', 2, '休闲零食',   'Snacks',            NULL, 20, 'STANDARD', NULL,
 '["仅销售预包装食品备案"]', 'PACKAGED_FOOD', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT730', 'CAT700', 2, '饮料冲调',   'Drinks',            NULL, 30, 'STANDARD', NULL,
 '["仅销售预包装食品备案"]', 'PACKAGED_FOOD', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT740', 'CAT700', 2, '烘焙面点',   'Bakery',            NULL, 40, 'STANDARD', NULL,
 '["食品经营许可证"]', 'FOOD', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT750', 'CAT700', 2, '婴幼儿食品', 'Baby Food',         NULL, 50, 'STANDARD', NULL,
 '["婴幼儿配方乳粉销售备案"]', 'INFANT_FORMULA', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('CAT250', 'CAT200', 2, '母婴用品', 'Baby Care',   NULL, 50, 'STANDARD', NULL, NULL, NULL,        'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT260', 'CAT200', 2, '宠物用品', 'Pet Supplies', NULL, 60, 'STANDARD', NULL, NULL, NULL,       'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT270', 'CAT200', 2, '宠物食品', 'Pet Food',    NULL, 70, 'STANDARD', NULL,
 '["饲料和饲料添加剂经营备案"]', 'PET_FOOD', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT280', 'CAT200', 2, '文具玩具', 'Stationery & Toys', NULL, 80, 'STANDARD', NULL, NULL, NULL,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('CAT800', NULL,     1, '鲜花绿植', 'Flowers & Plants', NULL, 80, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT810', 'CAT800', 2, '鲜花',     'Fresh Flowers',    NULL, 10, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT820', 'CAT800', 2, '绿植盆栽', 'Potted Plants',    NULL, 20, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('CAT330', 'CAT300', 2, '洗衣洗鞋', 'Laundry',      NULL, 30, 'SERVICE', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT340', 'CAT300', 2, '美容美发', 'Beauty & Hair', NULL, 40, 'SERVICE', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT350', 'CAT300', 2, '宠物洗护', 'Pet Grooming', NULL, 50, 'SERVICE', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT360', 'CAT300', 2, '跑腿代办', 'Errands',      NULL, 60, 'SERVICE', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
UPDATE prd_category SET status = 'ARCHIVED'
WHERE category_no IN ('CAT400', 'CAT500', 'CAT510', 'CAT520') AND status = 'ACTIVE';
UPDATE prd_category
SET required_code = 'PACKAGED_FOOD',
    qualification_required = '["仅销售预包装食品备案"]'
WHERE category_no = 'CAT130'
  AND status = 'ACTIVE'
  AND required_code IS NULL;
UPDATE sys_auth_code
SET enabled = 1
WHERE code IN ('FOOD', 'DRUG_RETAIL')
  AND enabled = 0;
UPDATE sys_function_point SET name = '平台类目树'
WHERE function_code = 'OPS_PRODUCT' AND name = '三级类目树';
UPDATE cmt_community
SET region_code = '330106',
    city_code   = '3301'
WHERE community_no IN ('C0001', 'C0002')
  AND region_code IS NULL;
INSERT INTO prd_spec_template
(template_no, scope, category_type, category_no, name, options, entity_no, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES

('SPT_CAT150_VOL', 'PLATFORM', 'FRESH', 'CAT150', '容量',
 '[{"code":"V250ML","label":"250ml"},{"code":"V500ML","label":"500ml"},{"code":"V750ML","label":"750ml"},{"code":"V1L","label":"1L"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT160_WT', 'PLATFORM', 'FRESH', 'CAT160', '重量',
 '[{"code":"W50G","label":"50g"},{"code":"W100G","label":"100g"},{"code":"W250G","label":"250g"},{"code":"W500G","label":"500g"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT180_VOL', 'PLATFORM', 'FRESH', 'CAT180', '容量',
 '[{"code":"V250ML","label":"250ml"},{"code":"V1L","label":"1L"},{"code":"V1500ML","label":"1.5L"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),

('SPT_CAT230_VOL', 'PLATFORM', 'NORMAL', 'CAT230', '容量',
 '[{"code":"V50ML","label":"50ml"},{"code":"V100ML","label":"100ml"},{"code":"V200ML","label":"200ml"},{"code":"V500ML","label":"500ml"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT250_SIZE', 'PLATFORM', 'NORMAL', 'CAT250', '尺码',
 '[{"code":"SZS","label":"S"},{"code":"SZM","label":"M"},{"code":"SZL","label":"L"},{"code":"SZXL","label":"XL"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT270_WT', 'PLATFORM', 'NORMAL', 'CAT270', '重量',
 '[{"code":"W500G","label":"500g"},{"code":"W1500G","label":"1.5kg"},{"code":"W5KG","label":"5kg"},{"code":"W10KG","label":"10kg"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),

('SPT_CAT610_COLOR', 'PLATFORM', 'NORMAL', 'CAT610', '颜色',
 '[{"code":"CLRBLACK","label":"黑色"},{"code":"CLRWHITE","label":"白色"},{"code":"CLRBLUE","label":"蓝色"},{"code":"CLRPINK","label":"粉色"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT610_STOR', 'PLATFORM', 'NORMAL', 'CAT610', '存储',
 '[{"code":"S64G","label":"64G"},{"code":"S128G","label":"128G"},{"code":"S256G","label":"256G"},{"code":"S512G","label":"512G"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT630_LEN', 'PLATFORM', 'NORMAL', 'CAT630', '长度',
 '[{"code":"L05M","label":"0.5m"},{"code":"L1M","label":"1m"},{"code":"L2M","label":"2m"},{"code":"L3M","label":"3m"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),

('SPT_CAT720_WT', 'PLATFORM', 'NORMAL', 'CAT720', '重量',
 '[{"code":"W100G","label":"100g"},{"code":"W250G","label":"250g"},{"code":"W500G","label":"500g"},{"code":"W1KG","label":"1kg"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT730_VOL', 'PLATFORM', 'NORMAL', 'CAT730', '容量',
 '[{"code":"V330ML","label":"330ml"},{"code":"V500ML","label":"500ml"},{"code":"V1L","label":"1L"},{"code":"VCASE","label":"整箱"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT750_STAGE', 'PLATFORM', 'NORMAL', 'CAT750', '段位',
 '[{"code":"ST1","label":"1段"},{"code":"ST2","label":"2段"},{"code":"ST3","label":"3段"},{"code":"ST4","label":"4段"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),

('SPT_CAT810_STEM', 'PLATFORM', 'NORMAL', 'CAT810', '支数',
 '[{"code":"N9","label":"9支"},{"code":"N11","label":"11支"},{"code":"N19","label":"19支"},{"code":"N33","label":"33支"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT820_SIZE', 'PLATFORM', 'NORMAL', 'CAT820', '尺寸',
 '[{"code":"PSMALL","label":"小盆"},{"code":"PMID","label":"中盆"},{"code":"PLARGE","label":"大盆"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),

('SPT_CAT310_ROOM', 'PLATFORM', 'SERVICE', 'CAT310', '房型',
 '[{"code":"R1","label":"一居"},{"code":"R2","label":"两居"},{"code":"R3","label":"三居"},{"code":"R4","label":"四居及以上"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_CAT350_PET', 'PLATFORM', 'SERVICE', 'CAT350', '体型',
 '[{"code":"PETS","label":"小型犬"},{"code":"PETM","label":"中型犬"},{"code":"PETL","label":"大型犬"},{"code":"PETCAT","label":"猫"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_COMMUNITY__TAB_REGIONS', 'OPS_COMMUNITY', '区划补录', '社区网格', '/communities?tab=regions', 'community:region:read', 'community:region:read', 'IMPLEMENTED', 1, 'P-2.1', 'MENU', 50, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_COMMUNITY__TAB_REGIONS');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__COMMUNITY_REGION_UPDATE', 'OPS_COMMUNITY', 'community:region:update', '页面内操作', NULL, 'community:region:update', 'community:region:update', 'IMPLEMENTED', 1, NULL, 'ACTION', 912, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__COMMUNITY_REGION_UPDATE');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_COMMUNITY__TAB_REGIONS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_COMMUNITY__TAB_REGIONS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__COMMUNITY_REGION_UPDATE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__COMMUNITY_REGION_UPDATE');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'OPS_COMMUNITY__TAB_REGIONS', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='OPS_COMMUNITY__TAB_REGIONS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'COMMUNITY_OPS', 'ACT__COMMUNITY_REGION_UPDATE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='COMMUNITY_OPS' AND x.point_code='ACT__COMMUNITY_REGION_UPDATE');
UPDATE cmt_community
   SET region_code = '330106002', coords_source = 'SEED'
 WHERE region_code = '330106' AND deleted = 0;
UPDATE sys_function_point
   SET name = '区划维护', updated_at = NOW()
 WHERE point_code = 'OPS_COMMUNITY__TAB_REGIONS';
UPDATE mch_service_area SET status = 'ACTIVE' WHERE level = 'STREET' AND status = 'PENDING';
UPDATE mch_store_audit SET status = 'PASSED', decided_at = UNIX_TIMESTAMP() * 1000, decided_by = 'SYSTEM' WHERE kind = 'SERVICE_AREA' AND status = 'PENDING' AND content LIKE 'STREET:%';
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__MERCHANT_FULFILLMENT_UPDATE', 'OPS_MERCHANT', 'merchant:fulfillment:update', '仅后端', NULL, 'merchant:fulfillment:update', 'merchant:fulfillment:update', 'IMPLEMENTED', 0, NULL, 'ACTION', 926, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__MERCHANT_FULFILLMENT_UPDATE');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'SUPER_ADMIN', 'ACT__MERCHANT_FULFILLMENT_UPDATE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='ACT__MERCHANT_FULFILLMENT_UPDATE' AND x.end_code='OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) SELECT 'BD', 'ACT__MERCHANT_FULFILLMENT_UPDATE', 'OPS', NOW(), NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='BD' AND x.point_code='ACT__MERCHANT_FULFILLMENT_UPDATE' AND x.end_code='OPS');
INSERT INTO sys_auth_code
(code, name, required_qualification, sort, enabled, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('FRESH_AQUATIC', '水产海鲜', '食品经营许可证', 18, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT140' AS category_no, 'CAT100' AS parent_no, 2 AS level, '熟食卤味' AS name, 'Deli' AS name_en,
 NULL AS icon, 40 AS sort, 'STANDARD' AS template, NULL AS attr_template,
 '["食品经营许可证"]' AS qualification_required, 'FOOD' AS required_code, 'ACTIVE' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT140');
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('CAT190', 'CAT100', 2, '水产海鲜', 'Seafood', NULL, 90, 'FRESH', NULL,
 '["食品经营许可证"]', 'FRESH_AQUATIC', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT290', 'CAT200', 2, '厨房用具', 'Kitchenware', NULL, 90, 'STANDARD', NULL,
 NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES

('CAT370', 'CAT300', 2, '洗车养护', 'Car Wash',        NULL, 70, 'SERVICE', NULL, NULL, NULL, 'ARCHIVED', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT380', 'CAT300', 2, '开锁换锁', 'Locksmith',       NULL, 80, 'SERVICE', NULL, NULL, NULL, 'ARCHIVED', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT390', 'CAT300', 2, '家电清洗', 'Appliance Clean', NULL, 90, 'SERVICE', NULL, NULL, NULL, 'ARCHIVED', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),

('CAT900', NULL,     1, '服饰鞋帽', 'Apparel',      NULL, 90, 'STANDARD', NULL, NULL, NULL, 'ARCHIVED', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT910', 'CAT900', 2, '内衣袜子', 'Underwear',    NULL, 10, 'STANDARD', NULL, NULL, NULL, 'ARCHIVED', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT920', 'CAT900', 2, '鞋类拖鞋', 'Shoes',        NULL, 20, 'STANDARD', NULL, NULL, NULL, 'ARCHIVED', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT930', 'CAT900', 2, '家纺床品', 'Home Textile', NULL, 30, 'STANDARD', NULL, NULL, NULL, 'ARCHIVED', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO prd_spec_dim (dim_no, code, name, value_type, unit, usage_type, universal, scope, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('SD_COLOR', 'COLOR', '颜色', 'ENUM', NULL, 'SALE', 1, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_SIZE', 'SIZE', '尺码', 'ENUM', NULL, 'SALE', 1, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_SIZE_GRADE', 'SIZE_GRADE', '尺寸', 'ENUM', NULL, 'SALE', 1, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_WEIGHT', 'WEIGHT', '重量', 'QUANT', 'g', 'SALE', 1, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_VOLUME', 'VOLUME', '容量', 'QUANT', 'ml', 'SALE', 1, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_COUNT', 'COUNT', '数量', 'ENUM', NULL, 'SALE', 1, 'PLATFORM', 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_PACK', 'PACK', '包装', 'ENUM', NULL, 'SALE', 1, 'PLATFORM', 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_FLAVOR', 'FLAVOR', '口味', 'ENUM', NULL, 'SALE', 1, 'PLATFORM', 80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_GRADE', 'GRADE', '等级', 'ENUM', NULL, 'SALE', 1, 'PLATFORM', 90, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_LENGTH', 'LENGTH', '长度', 'QUANT', 'm', 'SALE', 1, 'PLATFORM', 100, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_DIAMETER', 'DIAMETER', '口径', 'QUANT', 'cm', 'SALE', 1, 'PLATFORM', 110, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_DURATION', 'DURATION', '时长', 'QUANT', '分钟', 'SALE', 1, 'PLATFORM', 120, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_HEADCOUNT', 'HEADCOUNT', '人数', 'QUANT', '人', 'SALE', 1, 'PLATFORM', 130, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_MATERIAL', 'MATERIAL', '材质', 'ENUM', NULL, 'PROP', 1, 'PLATFORM', 140, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_ORIGIN', 'ORIGIN', '产地', 'ENUM', NULL, 'PROP', 1, 'PLATFORM', 150, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_SHELF_LIFE', 'SHELF_LIFE', '保质期', 'ENUM', NULL, 'PROP', 1, 'PLATFORM', 160, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_AGE', 'AGE', '适用年龄', 'ENUM', NULL, 'PROP', 1, 'PLATFORM', 170, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_POWER', 'POWER', '功率', 'QUANT', 'W', 'PROP', 1, 'PLATFORM', 180, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_STAGE', 'STAGE', '段位', 'ENUM', NULL, 'SALE', 0, 'PLATFORM', 210, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_ROOM', 'ROOM', '房型', 'ENUM', NULL, 'SALE', 0, 'PLATFORM', 220, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_PET_SIZE', 'PET_SIZE', '体型', 'ENUM', NULL, 'SALE', 0, 'PLATFORM', 230, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_STORAGE', 'STORAGE', '存储', 'ENUM', NULL, 'SALE', 0, 'PLATFORM', 240, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_STEM', 'STEM', '支数', 'QUANT', '支', 'SALE', 0, 'PLATFORM', 250, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_CUT', 'CUT', '处理方式', 'ENUM', NULL, 'SALE', 0, 'PLATFORM', 260, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_LAUNDRY_ITEM', 'LAUNDRY_ITEM', '衣物类型', 'ENUM', NULL, 'SALE', 0, 'PLATFORM', 270, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_BEAUTY_ITEM', 'BEAUTY_ITEM', '项目', 'ENUM', NULL, 'SALE', 0, 'PLATFORM', 280, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_DISTANCE', 'DISTANCE', '距离', 'QUANT', 'km', 'SALE', 0, 'PLATFORM', 290, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
INSERT INTO prd_spec_value (value_no, dim_no, code, label, numeric_value, numeric_unit, aliases, scope, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('SV_COLOR_CLRBLACK', 'SD_COLOR', 'CLRBLACK', '黑色', NULL, NULL, NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COLOR_CLRWHITE', 'SD_COLOR', 'CLRWHITE', '白色', NULL, NULL, NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COLOR_CLRGREY', 'SD_COLOR', 'CLRGREY', '灰色', NULL, NULL, NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COLOR_CLRSILVER', 'SD_COLOR', 'CLRSILVER', '银色', NULL, NULL, NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COLOR_CLRGOLD', 'SD_COLOR', 'CLRGOLD', '金色', NULL, NULL, NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COLOR_CLRRED', 'SD_COLOR', 'CLRRED', '红色', NULL, NULL, NULL, 'PLATFORM', 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COLOR_CLRPINK', 'SD_COLOR', 'CLRPINK', '粉色', NULL, NULL, NULL, 'PLATFORM', 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COLOR_CLRBLUE', 'SD_COLOR', 'CLRBLUE', '蓝色', NULL, NULL, NULL, 'PLATFORM', 80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COLOR_CLRGREEN', 'SD_COLOR', 'CLRGREEN', '绿色', NULL, NULL, NULL, 'PLATFORM', 90, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COLOR_CLRYELLOW', 'SD_COLOR', 'CLRYELLOW', '黄色', NULL, NULL, NULL, 'PLATFORM', 100, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COLOR_CLRBEIGE', 'SD_COLOR', 'CLRBEIGE', '米色', NULL, NULL, '["米白"]', 'PLATFORM', 110, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COLOR_CLRTRANS', 'SD_COLOR', 'CLRTRANS', '透明', NULL, NULL, NULL, 'PLATFORM', 120, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_SIZE_SZS', 'SD_SIZE', 'SZS', 'S', NULL, NULL, '["小码"]', 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_SIZE_SZM', 'SD_SIZE', 'SZM', 'M', NULL, NULL, '["中码"]', 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_SIZE_SZL', 'SD_SIZE', 'SZL', 'L', NULL, NULL, '["大码"]', 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_SIZE_SZXL', 'SD_SIZE', 'SZXL', 'XL', NULL, NULL, '["加大"]', 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_SIZE_SZXXL', 'SD_SIZE', 'SZXXL', 'XXL', NULL, NULL, NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_SIZE_SZONE', 'SD_SIZE', 'SZONE', '均码', NULL, NULL, '["统一码"]', 'PLATFORM', 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_SIZE_GRADE_PSMALL', 'SD_SIZE_GRADE', 'PSMALL', '小号', 1, NULL, '["小", "小盆"]', 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_SIZE_GRADE_PMID', 'SD_SIZE_GRADE', 'PMID', '中号', 2, NULL, '["中", "中盆"]', 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_SIZE_GRADE_PLARGE', 'SD_SIZE_GRADE', 'PLARGE', '大号', 3, NULL, '["大", "大盆"]', 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_SIZE_GRADE_PXLARGE', 'SD_SIZE_GRADE', 'PXLARGE', '加大号', 4, NULL, '["特大"]', 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W50G', 'SD_WEIGHT', 'W50G', '50g', 50, 'g', NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W100G', 'SD_WEIGHT', 'W100G', '100g', 100, 'g', '["二两"]', 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W200G', 'SD_WEIGHT', 'W200G', '200g', 200, 'g', NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W250G', 'SD_WEIGHT', 'W250G', '250g', 250, 'g', '["半斤"]', 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W500G', 'SD_WEIGHT', 'W500G', '500g', 500, 'g', '["1斤", "一斤"]', 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W750G', 'SD_WEIGHT', 'W750G', '750g', 750, 'g', '["1.5斤"]', 'PLATFORM', 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W1KG', 'SD_WEIGHT', 'W1KG', '1kg', 1000, 'g', '["2斤", "1公斤"]', 'PLATFORM', 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W1500G', 'SD_WEIGHT', 'W1500G', '1.5kg', 1500, 'g', '["3斤"]', 'PLATFORM', 80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W2KG', 'SD_WEIGHT', 'W2KG', '2kg', 2000, 'g', '["4斤"]', 'PLATFORM', 90, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W2500G', 'SD_WEIGHT', 'W2500G', '2.5kg', 2500, 'g', '["5斤"]', 'PLATFORM', 100, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W5KG', 'SD_WEIGHT', 'W5KG', '5kg', 5000, 'g', '["10斤"]', 'PLATFORM', 110, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W10KG', 'SD_WEIGHT', 'W10KG', '10kg', 10000, 'g', '["20斤"]', 'PLATFORM', 120, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V50ML', 'SD_VOLUME', 'V50ML', '50ml', 50, 'ml', NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V100ML', 'SD_VOLUME', 'V100ML', '100ml', 100, 'ml', NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V200ML', 'SD_VOLUME', 'V200ML', '200ml', 200, 'ml', NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V250ML', 'SD_VOLUME', 'V250ML', '250ml', 250, 'ml', NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V330ML', 'SD_VOLUME', 'V330ML', '330ml', 330, 'ml', NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V500ML', 'SD_VOLUME', 'V500ML', '500ml', 500, 'ml', NULL, 'PLATFORM', 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V750ML', 'SD_VOLUME', 'V750ML', '750ml', 750, 'ml', NULL, 'PLATFORM', 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V1L', 'SD_VOLUME', 'V1L', '1L', 1000, 'ml', '["1000ml", "1升"]', 'PLATFORM', 80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V1500ML', 'SD_VOLUME', 'V1500ML', '1.5L', 1500, 'ml', NULL, 'PLATFORM', 90, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V2L', 'SD_VOLUME', 'V2L', '2L', 2000, 'ml', NULL, 'PLATFORM', 100, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V4L', 'SD_VOLUME', 'V4L', '4L', 4000, 'ml', NULL, 'PLATFORM', 110, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V5L', 'SD_VOLUME', 'V5L', '5L', 5000, 'ml', NULL, 'PLATFORM', 120, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COUNT_C1', 'SD_COUNT', 'C1', '单件', 1, NULL, '["单个", "单包", "1件"]', 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COUNT_C2', 'SD_COUNT', 'C2', '2件装', 2, NULL, NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COUNT_C3', 'SD_COUNT', 'C3', '3件装', 3, NULL, NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COUNT_C4', 'SD_COUNT', 'C4', '4件装', 4, NULL, '["4只装"]', 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COUNT_C5', 'SD_COUNT', 'C5', '5件装', 5, NULL, NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COUNT_C6', 'SD_COUNT', 'C6', '6件装', 6, NULL, '["6只装", "6卷"]', 'PLATFORM', 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COUNT_C10', 'SD_COUNT', 'C10', '10件装', 10, NULL, NULL, 'PLATFORM', 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COUNT_C12', 'SD_COUNT', 'C12', '12件装', 12, NULL, '["12卷"]', 'PLATFORM', 80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COUNT_CSET', 'SD_COUNT', 'CSET', '套装', NULL, NULL, NULL, 'PLATFORM', 90, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COUNT_CCASE', 'SD_COUNT', 'CCASE', '整箱', NULL, NULL, '["整提", "整盒"]', 'PLATFORM', 100, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_PACK_PBULK', 'SD_PACK', 'PBULK', '散装', NULL, NULL, NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_PACK_PBAG', 'SD_PACK', 'PBAG', '袋装', NULL, NULL, NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_PACK_PBOX', 'SD_PACK', 'PBOX', '盒装', NULL, NULL, NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_PACK_PBOTTLE', 'SD_PACK', 'PBOTTLE', '瓶装', NULL, NULL, NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_PACK_PCAN', 'SD_PACK', 'PCAN', '罐装', NULL, NULL, NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_PACK_PBARREL', 'SD_PACK', 'PBARREL', '桶装', NULL, NULL, NULL, 'PLATFORM', 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_PACK_PGIFT', 'SD_PACK', 'PGIFT', '礼盒装', NULL, NULL, '["礼盒"]', 'PLATFORM', 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_PACK_PVACUUM', 'SD_PACK', 'PVACUUM', '真空装', NULL, NULL, NULL, 'PLATFORM', 80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FLAVOR_FLVPLAIN', 'SD_FLAVOR', 'FLVPLAIN', '原味', NULL, NULL, NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FLAVOR_FLVSPICE', 'SD_FLAVOR', 'FLVSPICE', '五香', NULL, NULL, NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FLAVOR_FLVSPICY', 'SD_FLAVOR', 'FLVSPICY', '香辣', NULL, NULL, NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FLAVOR_FLVMALA', 'SD_FLAVOR', 'FLVMALA', '麻辣', NULL, NULL, NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FLAVOR_FLVSWEET', 'SD_FLAVOR', 'FLVSWEET', '甜味', NULL, NULL, NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FLAVOR_FLVCHOCO', 'SD_FLAVOR', 'FLVCHOCO', '巧克力', NULL, NULL, NULL, 'PLATFORM', 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FLAVOR_FLVMATCHA', 'SD_FLAVOR', 'FLVMATCHA', '抹茶', NULL, NULL, NULL, 'PLATFORM', 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FLAVOR_FLVFLOSS', 'SD_FLAVOR', 'FLVFLOSS', '肉松', NULL, NULL, NULL, 'PLATFORM', 80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FLAVOR_FLVMILK', 'SD_FLAVOR', 'FLVMILK', '牛奶', NULL, NULL, '["奶味"]', 'PLATFORM', 90, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FLAVOR_FLVFRUIT', 'SD_FLAVOR', 'FLVFRUIT', '果味', NULL, NULL, NULL, 'PLATFORM', 100, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_GRADE_GRD1', 'SD_GRADE', 'GRD1', '普通', 1, NULL, NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_GRADE_GRD2', 'SD_GRADE', 'GRD2', '精选', 2, NULL, NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_GRADE_GRD3', 'SD_GRADE', 'GRD3', '特级', 3, NULL, NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_GRADE_GRD4', 'SD_GRADE', 'GRD4', '礼品级', 4, NULL, NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_LENGTH_L05M', 'SD_LENGTH', 'L05M', '0.5m', 0.5, 'm', NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_LENGTH_L1M', 'SD_LENGTH', 'L1M', '1m', 1, 'm', NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_LENGTH_L15M', 'SD_LENGTH', 'L15M', '1.5m', 1.5, 'm', NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_LENGTH_L2M', 'SD_LENGTH', 'L2M', '2m', 2, 'm', NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_LENGTH_L3M', 'SD_LENGTH', 'L3M', '3m', 3, 'm', NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_DIAMETER_DM16', 'SD_DIAMETER', 'DM16', '16cm', 16, 'cm', NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_DIAMETER_DM18', 'SD_DIAMETER', 'DM18', '18cm', 18, 'cm', NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_DIAMETER_DM20', 'SD_DIAMETER', 'DM20', '20cm', 20, 'cm', NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_DIAMETER_DM22', 'SD_DIAMETER', 'DM22', '22cm', 22, 'cm', NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_DIAMETER_DM24', 'SD_DIAMETER', 'DM24', '24cm', 24, 'cm', NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_DIAMETER_DM26', 'SD_DIAMETER', 'DM26', '26cm', 26, 'cm', NULL, 'PLATFORM', 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_DIAMETER_DM28', 'SD_DIAMETER', 'DM28', '28cm', 28, 'cm', NULL, 'PLATFORM', 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_DIAMETER_DM30', 'SD_DIAMETER', 'DM30', '30cm', 30, 'cm', NULL, 'PLATFORM', 80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_DURATION_D30', 'SD_DURATION', 'D30', '30分钟', 30, '分钟', NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_DURATION_D45', 'SD_DURATION', 'D45', '45分钟', 45, '分钟', NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_DURATION_D60', 'SD_DURATION', 'D60', '60分钟', 60, '分钟', '["1小时"]', 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_DURATION_D90', 'SD_DURATION', 'D90', '90分钟', 90, '分钟', NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_DURATION_D120', 'SD_DURATION', 'D120', '120分钟', 120, '分钟', '["2小时"]', 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_HEADCOUNT_H1', 'SD_HEADCOUNT', 'H1', '1人', 1, '人', NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_HEADCOUNT_H2', 'SD_HEADCOUNT', 'H2', '2人', 2, '人', NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_HEADCOUNT_H3', 'SD_HEADCOUNT', 'H3', '3人及以上', 3, '人', NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_MATERIAL_MATSTEEL', 'SD_MATERIAL', 'MATSTEEL', '不锈钢', NULL, NULL, NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_MATERIAL_MATIRON', 'SD_MATERIAL', 'MATIRON', '铸铁', NULL, NULL, NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_MATERIAL_MATCERAMIC', 'SD_MATERIAL', 'MATCERAMIC', '陶瓷', NULL, NULL, NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_MATERIAL_MATGLASS', 'SD_MATERIAL', 'MATGLASS', '玻璃', NULL, NULL, NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_MATERIAL_MATPLASTIC', 'SD_MATERIAL', 'MATPLASTIC', '塑料', NULL, NULL, NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_MATERIAL_MATWOOD', 'SD_MATERIAL', 'MATWOOD', '木质', NULL, NULL, NULL, 'PLATFORM', 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_MATERIAL_MATSILICONE', 'SD_MATERIAL', 'MATSILICONE', '硅胶', NULL, NULL, NULL, 'PLATFORM', 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_MATERIAL_MATALU', 'SD_MATERIAL', 'MATALU', '铝制', NULL, NULL, NULL, 'PLATFORM', 80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_MATERIAL_MATCOTTON', 'SD_MATERIAL', 'MATCOTTON', '纯棉', NULL, NULL, NULL, 'PLATFORM', 90, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_MATERIAL_MATBAMBOO', 'SD_MATERIAL', 'MATBAMBOO', '竹纤维', NULL, NULL, NULL, 'PLATFORM', 100, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_MATERIAL_MATPAPER', 'SD_MATERIAL', 'MATPAPER', '纸质', NULL, NULL, NULL, 'PLATFORM', 110, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_ORIGIN_ORGLOCAL', 'SD_ORIGIN', 'ORGLOCAL', '本地', NULL, NULL, NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_ORIGIN_ORGCN', 'SD_ORIGIN', 'ORGCN', '国产', NULL, NULL, NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_ORIGIN_ORGIMP', 'SD_ORIGIN', 'ORGIMP', '进口', NULL, NULL, NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_SHELF_LIFE_SLF7D', 'SD_SHELF_LIFE', 'SLF7D', '7天', 7, NULL, NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_SHELF_LIFE_SLF1M', 'SD_SHELF_LIFE', 'SLF1M', '1个月', 30, NULL, NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_SHELF_LIFE_SLF3M', 'SD_SHELF_LIFE', 'SLF3M', '3个月', 90, NULL, NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_SHELF_LIFE_SLF6M', 'SD_SHELF_LIFE', 'SLF6M', '6个月', 180, NULL, NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_SHELF_LIFE_SLF12M', 'SD_SHELF_LIFE', 'SLF12M', '12个月', 365, NULL, '["1年"]', 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_SHELF_LIFE_SLF18M', 'SD_SHELF_LIFE', 'SLF18M', '18个月', 540, NULL, NULL, 'PLATFORM', 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_SHELF_LIFE_SLF24M', 'SD_SHELF_LIFE', 'SLF24M', '24个月', 730, NULL, '["2年"]', 'PLATFORM', 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_AGE_AGE03', 'SD_AGE', 'AGE03', '0-3岁', 0, NULL, NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_AGE_AGE3', 'SD_AGE', 'AGE3', '3岁以上', 3, NULL, NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_AGE_AGE6', 'SD_AGE', 'AGE6', '6岁以上', 6, NULL, NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_AGE_AGE12', 'SD_AGE', 'AGE12', '12岁以上', 12, NULL, NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_AGE_AGEADULT', 'SD_AGE', 'AGEADULT', '成人', 18, NULL, NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_POWER_PWR800', 'SD_POWER', 'PWR800', '800W', 800, 'W', NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_POWER_PWR1000', 'SD_POWER', 'PWR1000', '1000W', 1000, 'W', NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_POWER_PWR1500', 'SD_POWER', 'PWR1500', '1500W', 1500, 'W', NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_POWER_PWR2000', 'SD_POWER', 'PWR2000', '2000W', 2000, 'W', NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_STAGE_ST1', 'SD_STAGE', 'ST1', '1段', 1, NULL, NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_STAGE_ST2', 'SD_STAGE', 'ST2', '2段', 2, NULL, NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_STAGE_ST3', 'SD_STAGE', 'ST3', '3段', 3, NULL, NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_STAGE_ST4', 'SD_STAGE', 'ST4', '4段', 4, NULL, NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_ROOM_R1', 'SD_ROOM', 'R1', '一居', 1, NULL, NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_ROOM_R2', 'SD_ROOM', 'R2', '两居', 2, NULL, NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_ROOM_R3', 'SD_ROOM', 'R3', '三居', 3, NULL, NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_ROOM_R4', 'SD_ROOM', 'R4', '四居及以上', 4, NULL, NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_PET_SIZE_PETS', 'SD_PET_SIZE', 'PETS', '小型犬', 1, NULL, NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_PET_SIZE_PETM', 'SD_PET_SIZE', 'PETM', '中型犬', 2, NULL, NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_PET_SIZE_PETL', 'SD_PET_SIZE', 'PETL', '大型犬', 3, NULL, NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_PET_SIZE_PETCAT', 'SD_PET_SIZE', 'PETCAT', '猫', NULL, NULL, NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_STORAGE_S64G', 'SD_STORAGE', 'S64G', '64G', 64, NULL, NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_STORAGE_S128G', 'SD_STORAGE', 'S128G', '128G', 128, NULL, NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_STORAGE_S256G', 'SD_STORAGE', 'S256G', '256G', 256, NULL, NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_STORAGE_S512G', 'SD_STORAGE', 'S512G', '512G', 512, NULL, NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_STORAGE_S1T', 'SD_STORAGE', 'S1T', '1T', 1024, NULL, NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_STEM_N9', 'SD_STEM', 'N9', '9支', 9, '支', NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_STEM_N11', 'SD_STEM', 'N11', '11支', 11, '支', NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_STEM_N19', 'SD_STEM', 'N19', '19支', 19, '支', NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_STEM_N33', 'SD_STEM', 'N33', '33支', 33, '支', NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_STEM_N99', 'SD_STEM', 'N99', '99支', 99, '支', NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_CUT_CUTWHOLE', 'SD_CUT', 'CUTWHOLE', '整只', NULL, NULL, '["整条"]', 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_CUT_CUTCHUNK', 'SD_CUT', 'CUTCHUNK', '切块', NULL, NULL, NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_CUT_CUTSLICE', 'SD_CUT', 'CUTSLICE', '切片', NULL, NULL, NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_CUT_CUTMINCE', 'SD_CUT', 'CUTMINCE', '绞馅', NULL, NULL, '["肉馅"]', 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_CUT_CUTBONE', 'SD_CUT', 'CUTBONE', '去骨', NULL, NULL, NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_LAUNDRY_ITEM_LDRTOP', 'SD_LAUNDRY_ITEM', 'LDRTOP', '上衣', NULL, NULL, NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_LAUNDRY_ITEM_LDRPANTS', 'SD_LAUNDRY_ITEM', 'LDRPANTS', '裤子', NULL, NULL, NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_LAUNDRY_ITEM_LDRCOAT', 'SD_LAUNDRY_ITEM', 'LDRCOAT', '外套', NULL, NULL, NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_LAUNDRY_ITEM_LDRSHOE', 'SD_LAUNDRY_ITEM', 'LDRSHOE', '鞋', NULL, NULL, NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_LAUNDRY_ITEM_LDRBED', 'SD_LAUNDRY_ITEM', 'LDRBED', '床品', NULL, NULL, NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_BEAUTY_ITEM_BTYCUT', 'SD_BEAUTY_ITEM', 'BTYCUT', '洗剪吹', NULL, NULL, NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_BEAUTY_ITEM_BTYPERM', 'SD_BEAUTY_ITEM', 'BTYPERM', '烫发', NULL, NULL, NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_BEAUTY_ITEM_BTYCOLOR', 'SD_BEAUTY_ITEM', 'BTYCOLOR', '染发', NULL, NULL, NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_BEAUTY_ITEM_BTYCARE', 'SD_BEAUTY_ITEM', 'BTYCARE', '护理', NULL, NULL, NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_BEAUTY_ITEM_BTYSTYLE', 'SD_BEAUTY_ITEM', 'BTYSTYLE', '造型', NULL, NULL, NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_DISTANCE_DIST3', 'SD_DISTANCE', 'DIST3', '3km内', 3, 'km', NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_DISTANCE_DIST5', 'SD_DISTANCE', 'DIST5', '5km内', 5, 'km', NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_DISTANCE_DIST10', 'SD_DISTANCE', 'DIST10', '10km内', 10, 'km', NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_DISTANCE_DIST15', 'SD_DISTANCE', 'DIST15', '15km内', 15, 'km', NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
INSERT INTO prd_category_spec (category_no, dim_no, usage_type, is_primary, required, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('CAT110', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT110', 'SD_PACK', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT110', 'SD_GRADE', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT110', 'SD_ORIGIN', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT110', 'SD_SHELF_LIFE', NULL, 0, 0, 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_GRADE', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_PACK', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_COUNT', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_ORIGIN', NULL, 0, 0, 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_PACK', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_FLAVOR', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_SHELF_LIFE', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_ORIGIN', NULL, 0, 0, 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT140', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT140', 'SD_FLAVOR', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT140', 'SD_PACK', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT140', 'SD_SHELF_LIFE', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_PACK', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_GRADE', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_ORIGIN', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_SHELF_LIFE', NULL, 0, 0, 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT170', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT170', 'SD_CUT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT170', 'SD_PACK', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT170', 'SD_ORIGIN', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT210', 'SD_COUNT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT210', 'SD_VOLUME', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT210', 'SD_PACK', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT210', 'SD_MATERIAL', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_SIZE_GRADE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_COLOR', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_COUNT', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_MATERIAL', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_VOLUME', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_COUNT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_PACK', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_AGE', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_SHELF_LIFE', NULL, 0, 0, 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_SIZE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_COUNT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_COLOR', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_AGE', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_MATERIAL', NULL, 0, 0, 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT260', 'SD_SIZE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT260', 'SD_PET_SIZE', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT260', 'SD_COLOR', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT260', 'SD_MATERIAL', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_PET_SIZE', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_FLAVOR', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_PACK', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_SHELF_LIFE', NULL, 0, 0, 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_COUNT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_COLOR', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_AGE', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_MATERIAL', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_DIAMETER', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_MATERIAL', 'SALE', 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_COUNT', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_COLOR', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT310', 'SD_ROOM', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT310', 'SD_DURATION', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT310', 'SD_HEADCOUNT', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT330', 'SD_COUNT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT330', 'SD_LAUNDRY_ITEM', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT340', 'SD_BEAUTY_ITEM', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT340', 'SD_DURATION', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT340', 'SD_HEADCOUNT', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT350', 'SD_PET_SIZE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT350', 'SD_DURATION', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT360', 'SD_DISTANCE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT360', 'SD_WEIGHT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT360', 'SD_DURATION', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT610', 'SD_COLOR', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT610', 'SD_STORAGE', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT610', 'SD_COUNT', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT620', 'SD_VOLUME', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT620', 'SD_COLOR', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT620', 'SD_POWER', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT620', 'SD_COUNT', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT630', 'SD_LENGTH', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT630', 'SD_COLOR', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT630', 'SD_COUNT', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT630', 'SD_MATERIAL', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_VOLUME', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_PACK', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_ORIGIN', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_SHELF_LIFE', NULL, 0, 0, 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_FLAVOR', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_PACK', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_SHELF_LIFE', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_VOLUME', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_COUNT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_FLAVOR', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_PACK', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_SHELF_LIFE', NULL, 0, 0, 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_COUNT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_FLAVOR', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_WEIGHT', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_SHELF_LIFE', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_STAGE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_WEIGHT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_PACK', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_AGE', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_SHELF_LIFE', NULL, 0, 0, 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT810', 'SD_STEM', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT810', 'SD_COLOR', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT810', 'SD_PACK', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT820', 'SD_SIZE_GRADE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT820', 'SD_COUNT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT820', 'SD_COLOR', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
INSERT INTO prd_category_spec_value (category_no, dim_no, value_no, label_override, sort, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('CAT110', 'SD_WEIGHT', 'SV_WEIGHT_W250G', '约半斤', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT110', 'SD_WEIGHT', 'SV_WEIGHT_W500G', '约1斤', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT110', 'SD_WEIGHT', 'SV_WEIGHT_W1KG', '约2斤', 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT110', 'SD_WEIGHT', 'SV_WEIGHT_W1500G', '约3斤', 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT110', 'SD_PACK', 'SV_PACK_PBULK', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT110', 'SD_PACK', 'SV_PACK_PBAG', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT110', 'SD_PACK', 'SV_PACK_PBOX', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT110', 'SD_GRADE', 'SV_GRADE_GRD1', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT110', 'SD_GRADE', 'SV_GRADE_GRD2', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT110', 'SD_GRADE', 'SV_GRADE_GRD3', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT110', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF7D', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT110', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF1M', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_WEIGHT', 'SV_WEIGHT_W500G', '约1斤', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_WEIGHT', 'SV_WEIGHT_W1KG', '约2斤', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_WEIGHT', 'SV_WEIGHT_W2500G', '约5斤', 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_WEIGHT', 'SV_WEIGHT_W5KG', '约10斤', 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_PACK', 'SV_PACK_PBULK', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_PACK', 'SV_PACK_PBAG', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_PACK', 'SV_PACK_PBOX', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_PACK', 'SV_PACK_PGIFT', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_COUNT', 'SV_COUNT_C1', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_COUNT', 'SV_COUNT_C4', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_COUNT', 'SV_COUNT_C6', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT120', 'SD_COUNT', 'SV_COUNT_CCASE', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_WEIGHT', 'SV_WEIGHT_W100G', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_WEIGHT', 'SV_WEIGHT_W250G', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_WEIGHT', 'SV_WEIGHT_W500G', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_WEIGHT', 'SV_WEIGHT_W1KG', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_PACK', 'SV_PACK_PBAG', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_PACK', 'SV_PACK_PBOX', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_PACK', 'SV_PACK_PCAN', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_PACK', 'SV_PACK_PVACUUM', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF3M', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF6M', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF12M', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT130', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF18M', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT140', 'SD_WEIGHT', 'SV_WEIGHT_W250G', '半斤', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT140', 'SD_WEIGHT', 'SV_WEIGHT_W500G', '1斤', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT140', 'SD_WEIGHT', 'SV_WEIGHT_W1KG', '2斤', 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT140', 'SD_FLAVOR', 'SV_FLAVOR_FLVPLAIN', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT140', 'SD_FLAVOR', 'SV_FLAVOR_FLVSPICE', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT140', 'SD_FLAVOR', 'SV_FLAVOR_FLVSPICY', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT140', 'SD_FLAVOR', 'SV_FLAVOR_FLVMALA', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT140', 'SD_PACK', 'SV_PACK_PBULK', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT140', 'SD_PACK', 'SV_PACK_PBOX', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT140', 'SD_PACK', 'SV_PACK_PVACUUM', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT140', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF7D', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT140', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF1M', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_WEIGHT', 'SV_WEIGHT_W50G', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_WEIGHT', 'SV_WEIGHT_W100G', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_WEIGHT', 'SV_WEIGHT_W250G', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_WEIGHT', 'SV_WEIGHT_W500G', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_PACK', 'SV_PACK_PBAG', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_PACK', 'SV_PACK_PCAN', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_PACK', 'SV_PACK_PBOX', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_PACK', 'SV_PACK_PGIFT', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF12M', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF18M', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT160', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF24M', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT170', 'SD_WEIGHT', 'SV_WEIGHT_W250G', '半斤', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT170', 'SD_WEIGHT', 'SV_WEIGHT_W500G', '1斤', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT170', 'SD_WEIGHT', 'SV_WEIGHT_W1KG', '2斤', 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT170', 'SD_WEIGHT', 'SV_WEIGHT_W2KG', '4斤', 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT170', 'SD_PACK', 'SV_PACK_PBULK', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT170', 'SD_PACK', 'SV_PACK_PBOX', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT170', 'SD_PACK', 'SV_PACK_PVACUUM', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT210', 'SD_COUNT', 'SV_COUNT_C1', '单包', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT210', 'SD_COUNT', 'SV_COUNT_C6', '6卷', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT210', 'SD_COUNT', 'SV_COUNT_C12', '12卷', 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT210', 'SD_COUNT', 'SV_COUNT_CCASE', '整提', 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT210', 'SD_VOLUME', 'SV_VOLUME_V500ML', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT210', 'SD_VOLUME', 'SV_VOLUME_V1L', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT210', 'SD_VOLUME', 'SV_VOLUME_V2L', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT210', 'SD_VOLUME', 'SV_VOLUME_V5L', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT210', 'SD_PACK', 'SV_PACK_PBAG', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT210', 'SD_PACK', 'SV_PACK_PBOTTLE', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT210', 'SD_PACK', 'SV_PACK_PBARREL', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT210', 'SD_MATERIAL', 'SV_MATERIAL_MATPAPER', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT210', 'SD_MATERIAL', 'SV_MATERIAL_MATCOTTON', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT210', 'SD_MATERIAL', 'SV_MATERIAL_MATBAMBOO', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_SIZE_GRADE', 'SV_SIZE_GRADE_PSMALL', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_SIZE_GRADE', 'SV_SIZE_GRADE_PMID', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_SIZE_GRADE', 'SV_SIZE_GRADE_PLARGE', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_COLOR', 'SV_COLOR_CLRBLACK', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_COLOR', 'SV_COLOR_CLRWHITE', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_COLOR', 'SV_COLOR_CLRGREY', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_COLOR', 'SV_COLOR_CLRBEIGE', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_COUNT', 'SV_COUNT_C1', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_COUNT', 'SV_COUNT_C2', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_COUNT', 'SV_COUNT_CSET', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_MATERIAL', 'SV_MATERIAL_MATPLASTIC', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_MATERIAL', 'SV_MATERIAL_MATWOOD', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_MATERIAL', 'SV_MATERIAL_MATCOTTON', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_MATERIAL', 'SV_MATERIAL_MATBAMBOO', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_MATERIAL', 'SV_MATERIAL_MATGLASS', NULL, 50, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_VOLUME', 'SV_VOLUME_V50ML', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_VOLUME', 'SV_VOLUME_V100ML', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_VOLUME', 'SV_VOLUME_V200ML', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_VOLUME', 'SV_VOLUME_V500ML', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_COUNT', 'SV_COUNT_C1', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_COUNT', 'SV_COUNT_C2', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_COUNT', 'SV_COUNT_CSET', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_PACK', 'SV_PACK_PBOTTLE', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_PACK', 'SV_PACK_PBOX', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_PACK', 'SV_PACK_PGIFT', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_AGE', 'SV_AGE_AGE03', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_AGE', 'SV_AGE_AGE12', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_AGE', 'SV_AGE_AGEADULT', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF12M', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT230', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF24M', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_SIZE', 'SV_SIZE_SZS', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_SIZE', 'SV_SIZE_SZM', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_SIZE', 'SV_SIZE_SZL', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_SIZE', 'SV_SIZE_SZXL', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_COUNT', 'SV_COUNT_C1', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_COUNT', 'SV_COUNT_C2', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_COUNT', 'SV_COUNT_C5', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_COUNT', 'SV_COUNT_CCASE', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_COLOR', 'SV_COLOR_CLRWHITE', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_COLOR', 'SV_COLOR_CLRPINK', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_COLOR', 'SV_COLOR_CLRBLUE', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_COLOR', 'SV_COLOR_CLRYELLOW', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_AGE', 'SV_AGE_AGE03', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_AGE', 'SV_AGE_AGE3', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_MATERIAL', 'SV_MATERIAL_MATCOTTON', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_MATERIAL', 'SV_MATERIAL_MATSILICONE', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT250', 'SD_MATERIAL', 'SV_MATERIAL_MATPLASTIC', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT260', 'SD_SIZE', 'SV_SIZE_SZS', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT260', 'SD_SIZE', 'SV_SIZE_SZM', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT260', 'SD_SIZE', 'SV_SIZE_SZL', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT260', 'SD_SIZE', 'SV_SIZE_SZXL', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT260', 'SD_COLOR', 'SV_COLOR_CLRBLACK', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT260', 'SD_COLOR', 'SV_COLOR_CLRGREY', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT260', 'SD_COLOR', 'SV_COLOR_CLRPINK', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT260', 'SD_COLOR', 'SV_COLOR_CLRBLUE', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT260', 'SD_MATERIAL', 'SV_MATERIAL_MATPLASTIC', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT260', 'SD_MATERIAL', 'SV_MATERIAL_MATCOTTON', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT260', 'SD_MATERIAL', 'SV_MATERIAL_MATSILICONE', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT260', 'SD_MATERIAL', 'SV_MATERIAL_MATSTEEL', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_WEIGHT', 'SV_WEIGHT_W500G', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_WEIGHT', 'SV_WEIGHT_W1500G', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_WEIGHT', 'SV_WEIGHT_W2500G', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_WEIGHT', 'SV_WEIGHT_W5KG', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_WEIGHT', 'SV_WEIGHT_W10KG', NULL, 50, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_FLAVOR', 'SV_FLAVOR_FLVPLAIN', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_FLAVOR', 'SV_FLAVOR_FLVMILK', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_FLAVOR', 'SV_FLAVOR_FLVFRUIT', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_PACK', 'SV_PACK_PBAG', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_PACK', 'SV_PACK_PCAN', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_PACK', 'SV_PACK_PBOX', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF6M', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF12M', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT270', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF18M', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_COUNT', 'SV_COUNT_C1', '单个', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_COUNT', 'SV_COUNT_C2', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_COUNT', 'SV_COUNT_C5', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_COUNT', 'SV_COUNT_CSET', '套装', 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_COLOR', 'SV_COLOR_CLRRED', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_COLOR', 'SV_COLOR_CLRBLUE', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_COLOR', 'SV_COLOR_CLRGREEN', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_COLOR', 'SV_COLOR_CLRYELLOW', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_COLOR', 'SV_COLOR_CLRBLACK', NULL, 50, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_AGE', 'SV_AGE_AGE03', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_AGE', 'SV_AGE_AGE3', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_AGE', 'SV_AGE_AGE6', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_AGE', 'SV_AGE_AGE12', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_MATERIAL', 'SV_MATERIAL_MATPLASTIC', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_MATERIAL', 'SV_MATERIAL_MATWOOD', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT280', 'SD_MATERIAL', 'SV_MATERIAL_MATPAPER', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_DIAMETER', 'SV_DIAMETER_DM16', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_DIAMETER', 'SV_DIAMETER_DM20', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_DIAMETER', 'SV_DIAMETER_DM24', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_DIAMETER', 'SV_DIAMETER_DM28', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_MATERIAL', 'SV_MATERIAL_MATSTEEL', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_MATERIAL', 'SV_MATERIAL_MATIRON', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_MATERIAL', 'SV_MATERIAL_MATCERAMIC', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_MATERIAL', 'SV_MATERIAL_MATGLASS', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_MATERIAL', 'SV_MATERIAL_MATALU', NULL, 50, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_COUNT', 'SV_COUNT_C1', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_COUNT', 'SV_COUNT_C2', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_COUNT', 'SV_COUNT_CSET', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_COLOR', 'SV_COLOR_CLRBLACK', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_COLOR', 'SV_COLOR_CLRSILVER', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT290', 'SD_COLOR', 'SV_COLOR_CLRWHITE', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT310', 'SD_DURATION', 'SV_DURATION_D60', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT310', 'SD_DURATION', 'SV_DURATION_D90', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT310', 'SD_DURATION', 'SV_DURATION_D120', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT330', 'SD_COUNT', 'SV_COUNT_C1', '1件', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT330', 'SD_COUNT', 'SV_COUNT_C3', '3件', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT330', 'SD_COUNT', 'SV_COUNT_C5', '5件', 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT330', 'SD_COUNT', 'SV_COUNT_C10', '10件', 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT340', 'SD_DURATION', 'SV_DURATION_D30', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT340', 'SD_DURATION', 'SV_DURATION_D60', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT340', 'SD_DURATION', 'SV_DURATION_D90', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT340', 'SD_DURATION', 'SV_DURATION_D120', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT340', 'SD_HEADCOUNT', 'SV_HEADCOUNT_H1', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT340', 'SD_HEADCOUNT', 'SV_HEADCOUNT_H2', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT350', 'SD_DURATION', 'SV_DURATION_D30', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT350', 'SD_DURATION', 'SV_DURATION_D60', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT350', 'SD_DURATION', 'SV_DURATION_D90', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT360', 'SD_WEIGHT', 'SV_WEIGHT_W5KG', '5kg内', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT360', 'SD_WEIGHT', 'SV_WEIGHT_W10KG', '10kg内', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT360', 'SD_DURATION', 'SV_DURATION_D30', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT360', 'SD_DURATION', 'SV_DURATION_D60', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT610', 'SD_COLOR', 'SV_COLOR_CLRBLACK', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT610', 'SD_COLOR', 'SV_COLOR_CLRWHITE', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT610', 'SD_COLOR', 'SV_COLOR_CLRSILVER', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT610', 'SD_COLOR', 'SV_COLOR_CLRGOLD', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT610', 'SD_COLOR', 'SV_COLOR_CLRBLUE', NULL, 50, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT610', 'SD_COUNT', 'SV_COUNT_C1', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT610', 'SD_COUNT', 'SV_COUNT_CSET', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT620', 'SD_VOLUME', 'SV_VOLUME_V1L', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT620', 'SD_VOLUME', 'SV_VOLUME_V1500ML', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT620', 'SD_VOLUME', 'SV_VOLUME_V2L', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT620', 'SD_VOLUME', 'SV_VOLUME_V4L', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT620', 'SD_COLOR', 'SV_COLOR_CLRBLACK', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT620', 'SD_COLOR', 'SV_COLOR_CLRWHITE', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT620', 'SD_COLOR', 'SV_COLOR_CLRSILVER', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT620', 'SD_COUNT', 'SV_COUNT_C1', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT620', 'SD_COUNT', 'SV_COUNT_CSET', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT630', 'SD_LENGTH', 'SV_LENGTH_L05M', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT630', 'SD_LENGTH', 'SV_LENGTH_L1M', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT630', 'SD_LENGTH', 'SV_LENGTH_L2M', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT630', 'SD_LENGTH', 'SV_LENGTH_L3M', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT630', 'SD_COLOR', 'SV_COLOR_CLRBLACK', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT630', 'SD_COLOR', 'SV_COLOR_CLRWHITE', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT630', 'SD_COLOR', 'SV_COLOR_CLRGREY', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT630', 'SD_COUNT', 'SV_COUNT_C1', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT630', 'SD_COUNT', 'SV_COUNT_C2', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT630', 'SD_COUNT', 'SV_COUNT_C5', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT630', 'SD_MATERIAL', 'SV_MATERIAL_MATPLASTIC', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT630', 'SD_MATERIAL', 'SV_MATERIAL_MATSILICONE', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT630', 'SD_MATERIAL', 'SV_MATERIAL_MATALU', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_WEIGHT', 'SV_WEIGHT_W500G', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_WEIGHT', 'SV_WEIGHT_W1KG', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_WEIGHT', 'SV_WEIGHT_W2500G', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_WEIGHT', 'SV_WEIGHT_W5KG', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_WEIGHT', 'SV_WEIGHT_W10KG', NULL, 50, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_VOLUME', 'SV_VOLUME_V500ML', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_VOLUME', 'SV_VOLUME_V1L', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_VOLUME', 'SV_VOLUME_V2L', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_VOLUME', 'SV_VOLUME_V5L', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_PACK', 'SV_PACK_PBAG', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_PACK', 'SV_PACK_PBOTTLE', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_PACK', 'SV_PACK_PBARREL', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF12M', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF18M', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT710', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF24M', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_WEIGHT', 'SV_WEIGHT_W100G', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_WEIGHT', 'SV_WEIGHT_W250G', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_WEIGHT', 'SV_WEIGHT_W500G', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_WEIGHT', 'SV_WEIGHT_W1KG', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_PACK', 'SV_PACK_PBAG', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_PACK', 'SV_PACK_PBOX', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_PACK', 'SV_PACK_PCAN', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_PACK', 'SV_PACK_PGIFT', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF3M', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF6M', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT720', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF12M', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_VOLUME', 'SV_VOLUME_V250ML', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_VOLUME', 'SV_VOLUME_V330ML', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_VOLUME', 'SV_VOLUME_V500ML', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_VOLUME', 'SV_VOLUME_V1L', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_COUNT', 'SV_COUNT_C1', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_COUNT', 'SV_COUNT_C6', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_COUNT', 'SV_COUNT_C12', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_COUNT', 'SV_COUNT_CCASE', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_FLAVOR', 'SV_FLAVOR_FLVPLAIN', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_FLAVOR', 'SV_FLAVOR_FLVMILK', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_FLAVOR', 'SV_FLAVOR_FLVFRUIT', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_FLAVOR', 'SV_FLAVOR_FLVSWEET', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_PACK', 'SV_PACK_PBOTTLE', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_PACK', 'SV_PACK_PCAN', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_PACK', 'SV_PACK_PBOX', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF6M', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT730', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF12M', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_COUNT', 'SV_COUNT_C1', '单个', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_COUNT', 'SV_COUNT_C4', '4只装', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_COUNT', 'SV_COUNT_C6', '6只装', 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_COUNT', 'SV_COUNT_CCASE', '整盒', 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_FLAVOR', 'SV_FLAVOR_FLVPLAIN', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_FLAVOR', 'SV_FLAVOR_FLVCHOCO', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_FLAVOR', 'SV_FLAVOR_FLVMATCHA', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_FLAVOR', 'SV_FLAVOR_FLVFLOSS', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_FLAVOR', 'SV_FLAVOR_FLVMILK', NULL, 50, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_WEIGHT', 'SV_WEIGHT_W250G', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_WEIGHT', 'SV_WEIGHT_W500G', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_WEIGHT', 'SV_WEIGHT_W1KG', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF7D', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT740', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF1M', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_WEIGHT', 'SV_WEIGHT_W100G', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_WEIGHT', 'SV_WEIGHT_W250G', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_WEIGHT', 'SV_WEIGHT_W500G', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_WEIGHT', 'SV_WEIGHT_W1KG', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_PACK', 'SV_PACK_PBAG', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_PACK', 'SV_PACK_PBOX', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_PACK', 'SV_PACK_PCAN', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_AGE', 'SV_AGE_AGE03', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_AGE', 'SV_AGE_AGE3', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF12M', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF18M', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT750', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF24M', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT810', 'SD_COLOR', 'SV_COLOR_CLRRED', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT810', 'SD_COLOR', 'SV_COLOR_CLRPINK', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT810', 'SD_COLOR', 'SV_COLOR_CLRWHITE', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT810', 'SD_COLOR', 'SV_COLOR_CLRYELLOW', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT810', 'SD_COLOR', 'SV_COLOR_CLRBLUE', NULL, 50, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT810', 'SD_PACK', 'SV_PACK_PBOX', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT810', 'SD_PACK', 'SV_PACK_PGIFT', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT820', 'SD_SIZE_GRADE', 'SV_SIZE_GRADE_PSMALL', '小盆', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT820', 'SD_SIZE_GRADE', 'SV_SIZE_GRADE_PMID', '中盆', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT820', 'SD_SIZE_GRADE', 'SV_SIZE_GRADE_PLARGE', '大盆', 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT820', 'SD_COUNT', 'SV_COUNT_C1', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT820', 'SD_COUNT', 'SV_COUNT_C2', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT820', 'SD_COUNT', 'SV_COUNT_CSET', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT820', 'SD_COLOR', 'SV_COLOR_CLRGREEN', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT820', 'SD_COLOR', 'SV_COLOR_CLRRED', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT820', 'SD_COLOR', 'SV_COLOR_CLRWHITE', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
INSERT INTO prd_category_spec (category_no, dim_no, usage_type, is_primary, required, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('CAT240', 'SD_COUNT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_WEIGHT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_AGE', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_SHELF_LIFE', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_DURATION', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_HEADCOUNT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_DISTANCE', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
INSERT INTO prd_category_spec_value (category_no, dim_no, value_no, label_override, sort, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('CAT240', 'SD_COUNT', 'SV_COUNT_C1', '单盒', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_COUNT', 'SV_COUNT_C2', '2盒装', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_COUNT', 'SV_COUNT_C3', '3盒装', 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_COUNT', 'SV_COUNT_CSET', '套装', 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_WEIGHT', 'SV_WEIGHT_W50G', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_WEIGHT', 'SV_WEIGHT_W100G', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_WEIGHT', 'SV_WEIGHT_W250G', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_AGE', 'SV_AGE_AGE03', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_AGE', 'SV_AGE_AGE12', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_AGE', 'SV_AGE_AGEADULT', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF12M', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF18M', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF24M', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  
  ('CAT320', 'SD_DURATION', 'SV_DURATION_D30', '30分钟内', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_DURATION', 'SV_DURATION_D60', '1小时', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_DURATION', 'SV_DURATION_D120', '2小时', 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_HEADCOUNT', 'SV_HEADCOUNT_H1', '1人上门', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_HEADCOUNT', 'SV_HEADCOUNT_H2', '2人上门', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_DISTANCE', 'SV_DISTANCE_DIST3', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_DISTANCE', 'SV_DISTANCE_DIST5', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_DISTANCE', 'SV_DISTANCE_DIST10', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
UPDATE prd_category SET status = 'ACTIVE', updated_at = NOW(), updated_by = 'SYSTEM'
 WHERE category_no IN ('CAT170', 'CAT180', 'CAT190');
INSERT INTO prd_category_spec (category_no, dim_no, usage_type, is_primary, required, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('CAT180', 'SD_VOLUME', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_COUNT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_PACK', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_SHELF_LIFE', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_CUT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_PACK', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_ORIGIN', NULL, 0, 0, 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
INSERT INTO prd_category_spec_value (category_no, dim_no, value_no, label_override, sort, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  
  ('CAT180', 'SD_VOLUME', 'SV_VOLUME_V200ML', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_VOLUME', 'SV_VOLUME_V250ML', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_VOLUME', 'SV_VOLUME_V500ML', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_VOLUME', 'SV_VOLUME_V1L', NULL, 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_COUNT', 'SV_COUNT_C1', '单盒', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_COUNT', 'SV_COUNT_C6', '6盒装', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_COUNT', 'SV_COUNT_C12', '12盒装', 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_COUNT', 'SV_COUNT_CCASE', '整箱', 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_PACK', 'SV_PACK_PBOX', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_PACK', 'SV_PACK_PBOTTLE', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_PACK', 'SV_PACK_PBAG', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  
  ('CAT180', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF7D', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF1M', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT180', 'SD_SHELF_LIFE', 'SV_SHELF_LIFE_SLF6M', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  
  ('CAT190', 'SD_WEIGHT', 'SV_WEIGHT_W250G', '半斤', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_WEIGHT', 'SV_WEIGHT_W500G', '1斤', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_WEIGHT', 'SV_WEIGHT_W1KG', '2斤', 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_WEIGHT', 'SV_WEIGHT_W2KG', '4斤', 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_CUT', 'SV_CUT_CUTWHOLE', '整条', 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_CUT', 'SV_CUT_CUTCHUNK', '切段', 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_CUT', 'SV_CUT_CUTSLICE', '切片', 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_CUT', 'SV_CUT_CUTBONE', '去骨', 40, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_PACK', 'SV_PACK_PBULK', NULL, 10, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_PACK', 'SV_PACK_PBOX', NULL, 20, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT190', 'SD_PACK', 'SV_PACK_PVACUUM', NULL, 30, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_PRODUCT__TAB_SPEC_COMMON', 'OPS_PRODUCT', '通用规格', '规格', '/products?tab=spec-common', 'product:spec:read', 'product:spec:read', 'IMPLEMENTED', 1, 'P-3.4', 'MENU', 60, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_PRODUCT__TAB_SPEC_COMMON');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_PRODUCT__TAB_SPEC_SPECIAL', 'OPS_PRODUCT', '专用规格', '规格', '/products?tab=spec-special', 'product:spec:read', 'product:spec:read', 'IMPLEMENTED', 1, 'P-3.4', 'MENU', 61, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_PRODUCT__TAB_SPEC_SPECIAL');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_PRODUCT__TAB_CATEGORY_SPEC', 'OPS_PRODUCT', '类目 × 规格', '规格', '/products?tab=category-spec', 'product:spec:read', 'product:spec:read', 'IMPLEMENTED', 1, 'P-3.4', 'MENU', 62, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_PRODUCT__TAB_CATEGORY_SPEC');
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__PRODUCT_SPEC_UPDATE', 'OPS_PRODUCT', '规格库维护', '规格', NULL, 'product:spec:update', 'product:spec:update', 'IMPLEMENTED', 1, 'P-3.4', 'ACTION', 63, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='ACT__PRODUCT_SPEC_UPDATE');
UPDATE sys_auth_code SET qual_type = 'BUSINESS_LICENSE'
 WHERE required_qualification LIKE '营业执照%';
UPDATE sys_auth_code SET qual_type = 'FOOD_PERMIT'
 WHERE required_qualification LIKE '食品经营许可证%';
UPDATE sys_auth_code SET qual_type = 'OTHER'
 WHERE required_qualification LIKE '%备案%';
UPDATE sys_auth_code SET qual_type = 'OTHER'
 WHERE required_qualification LIKE '%药品经营许可证%';
UPDATE sys_auth_code SET qual_type = 'OTHER'
 WHERE required_qualification LIKE '%维修资质%';
UPDATE cmt_community SET source = 'OFFICIAL' WHERE source IS NULL AND origin_code IS NOT NULL AND origin_code <> '';
UPDATE cmt_community SET source = 'MAP' WHERE source IS NULL AND coords_source = 'AMAP';
UPDATE prd_category SET name_en = 'Deli', updated_at = NOW()
 WHERE category_no = 'CAT140' AND deleted = 0 AND (name_en IS NULL OR name_en = '');
UPDATE prd_category SET name_en = 'Health & Medicine', updated_at = NOW()
 WHERE category_no = 'CAT240' AND deleted = 0 AND (name_en IS NULL OR name_en = '');
UPDATE prd_category SET name_en = 'Repair & Install', updated_at = NOW()
 WHERE category_no = 'CAT320' AND deleted = 0 AND (name_en IS NULL OR name_en = '');
UPDATE sys_region
SET rural = 1
WHERE level = 'VILLAGE' AND name REGEXP '(村委会|村民委员会)$';
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT145' AS category_no, 'CAT100' AS parent_no, 2 AS level, '豆制品' AS name, 'Bean Products' AS name_en,
 NULL AS icon, 45 AS sort, 'FRESH' AS template, NULL AS attr_template,
 '["食品经营许可证"]' AS qualification_required, 'FOOD' AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT145');
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT185' AS category_no, 'CAT100' AS parent_no, 2 AS level, '预制半成品菜' AS name, 'Prepared Dishes' AS name_en,
 NULL AS icon, 85 AS sort, 'STANDARD' AS template, NULL AS attr_template,
 '["食品经营许可证"]' AS qualification_required, 'FOOD' AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT185');
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT195' AS category_no, 'CAT100' AS parent_no, 2 AS level, '冷冻速食' AS name, 'Frozen Food' AS name_en,
 NULL AS icon, 95 AS sort, 'STANDARD' AS template, NULL AS attr_template,
 '["仅销售预包装食品备案"]' AS qualification_required, 'PACKAGED_FOOD' AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT195');
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT295' AS category_no, 'CAT200' AS parent_no, 2 AS level, '洗护清洁' AS name, 'Cleaning & Care' AS name_en,
 NULL AS icon, 15 AS sort, 'STANDARD' AS template, NULL AS attr_template,
 NULL AS qualification_required, 'DAILY' AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT295');
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT285' AS category_no, 'CAT200' AS parent_no, 2 AS level, '五金电料' AS name, 'Hardware & Electrical' AS name_en,
 NULL AS icon, 85 AS sort, 'STANDARD' AS template, NULL AS attr_template,
 NULL AS qualification_required, 'DAILY' AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT285');
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT375' AS category_no, 'CAT300' AS parent_no, 2 AS level, '照护陪护' AS name, 'Caregiving' AS name_en,
 NULL AS icon, 75 AS sort, 'SERVICE' AS template, NULL AS attr_template,
 NULL AS qualification_required, 'HOUSEKEEPING' AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT375');
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT385' AS category_no, 'CAT300' AS parent_no, 2 AS level, '搬家搬运' AS name, 'Moving & Hauling' AS name_en,
 NULL AS icon, 85 AS sort, 'SERVICE' AS template, NULL AS attr_template,
 NULL AS qualification_required, NULL AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT385');
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT395' AS category_no, 'CAT300' AS parent_no, 2 AS level, '回收' AS name, 'Recycling' AS name_en,
 NULL AS icon, 95 AS sort, 'SERVICE' AS template, NULL AS attr_template,
 NULL AS qualification_required, NULL AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT395');
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT760' AS category_no, 'CAT700' AS parent_no, 2 AS level, '方便速食' AS name, 'Instant Food' AS name_en,
 NULL AS icon, 60 AS sort, 'STANDARD' AS template, NULL AS attr_template,
 '["仅销售预包装食品备案"]' AS qualification_required, 'PACKAGED_FOOD' AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT760');
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT410' AS category_no, 'CAT400' AS parent_no, 2 AS level, '服务次卡' AS name, 'Service Passes' AS name_en,
 NULL AS icon, 10 AS sort, 'VOUCHER' AS template, NULL AS attr_template,
 NULL AS qualification_required, NULL AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT410');
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT420' AS category_no, 'CAT400' AS parent_no, 2 AS level, '代金券' AS name, 'Gift Vouchers' AS name_en,
 NULL AS icon, 20 AS sort, 'VOUCHER' AS template, NULL AS attr_template,
 NULL AS qualification_required, NULL AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT420');
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT530' AS category_no, 'CAT500' AS parent_no, 2 AS level, '生活缴费' AS name, 'Utility Payments' AS name_en,
 NULL AS icon, 30 AS sort, 'VIRTUAL' AS template, NULL AS attr_template,
 NULL AS qualification_required, NULL AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT530');
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT540' AS category_no, 'CAT500' AS parent_no, 2 AS level, '交通出行卡' AS name, 'Transit Cards' AS name_en,
 NULL AS icon, 40 AS sort, 'VIRTUAL' AS template, NULL AS attr_template,
 NULL AS qualification_required, NULL AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT540');
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT940' AS category_no, 'CAT900' AS parent_no, 2 AS level, '成人服饰' AS name, 'Apparel' AS name_en,
 NULL AS icon, 40 AS sort, 'STANDARD' AS template, NULL AS attr_template,
 NULL AS qualification_required, NULL AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT940');
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT * FROM (SELECT
 'CAT950' AS category_no, 'CAT900' AS parent_no, 2 AS level, '童装' AS name, 'Kidswear' AS name_en,
 NULL AS icon, 50 AS sort, 'STANDARD' AS template, NULL AS attr_template,
 NULL AS qualification_required, NULL AS required_code, 'ARCHIVED' AS status,
 'MAIN' AS tenant_no, NOW() AS created_at, 'SYSTEM' AS created_by, NOW() AS updated_at,
 'SYSTEM' AS updated_by, 0 AS version, 0 AS deleted) t
WHERE NOT EXISTS (SELECT 1 FROM prd_category c WHERE c.category_no = 'CAT950');
INSERT INTO prd_spec_dim (dim_no, code, name, value_type, unit, usage_type, universal, scope, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('SD_FACE_VALUE', 'FACE_VALUE', '面值', 'QUANT', '元', 'SALE', 0, 'PLATFORM', 280, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SD_TIMES', 'TIMES', '次数', 'QUANT', '次', 'SALE', 0, 'PLATFORM', 290, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
INSERT INTO prd_spec_value (value_no, dim_no, code, label, numeric_value, numeric_unit, aliases, scope, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('SV_FACE_VALUE_F10', 'SD_FACE_VALUE', 'F10', '10元', 10, '元', NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FACE_VALUE_F20', 'SD_FACE_VALUE', 'F20', '20元', 20, '元', NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FACE_VALUE_F30', 'SD_FACE_VALUE', 'F30', '30元', 30, '元', NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FACE_VALUE_F50', 'SD_FACE_VALUE', 'F50', '50元', 50, '元', NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FACE_VALUE_F100', 'SD_FACE_VALUE', 'F100', '100元', 100, '元', NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FACE_VALUE_F200', 'SD_FACE_VALUE', 'F200', '200元', 200, '元', NULL, 'PLATFORM', 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FACE_VALUE_F500', 'SD_FACE_VALUE', 'F500', '500元', 500, '元', NULL, 'PLATFORM', 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_FACE_VALUE_F1000', 'SD_FACE_VALUE', 'F1000', '1000元', 1000, '元', NULL, 'PLATFORM', 80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_TIMES_T1', 'SD_TIMES', 'T1', '1次', 1, '次', NULL, 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_TIMES_T3', 'SD_TIMES', 'T3', '3次', 3, '次', NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_TIMES_T5', 'SD_TIMES', 'T5', '5次', 5, '次', NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_TIMES_T10', 'SD_TIMES', 'T10', '10次', 10, '次', NULL, 'PLATFORM', 40, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_TIMES_T20', 'SD_TIMES', 'T20', '20次', 20, '次', NULL, 'PLATFORM', 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_TIMES_T30', 'SD_TIMES', 'T30', '30次', 30, '次', NULL, 'PLATFORM', 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
INSERT INTO prd_category_spec (category_no, dim_no, usage_type, is_primary, required, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('CAT145', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT145', 'SD_PACK', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT185', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT185', 'SD_FLAVOR', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT185', 'SD_SHELF_LIFE', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT195', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT195', 'SD_FLAVOR', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT195', 'SD_PACK', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT295', 'SD_VOLUME', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT295', 'SD_PACK', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT295', 'SD_COUNT', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT285', 'SD_COUNT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT285', 'SD_SIZE_GRADE', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT285', 'SD_MATERIAL', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT375', 'SD_DURATION', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT375', 'SD_HEADCOUNT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT385', 'SD_DISTANCE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT385', 'SD_ROOM', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT385', 'SD_HEADCOUNT', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT395', 'SD_WEIGHT', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT395', 'SD_DISTANCE', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT760', 'SD_FLAVOR', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT760', 'SD_COUNT', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT760', 'SD_WEIGHT', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT410', 'SD_TIMES', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT410', 'SD_DURATION', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT420', 'SD_FACE_VALUE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT530', 'SD_FACE_VALUE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT540', 'SD_FACE_VALUE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT940', 'SD_SIZE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT940', 'SD_COLOR', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT950', 'SD_SIZE', NULL, 1, 0, 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT950', 'SD_COLOR', NULL, 0, 0, 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT950', 'SD_AGE', NULL, 0, 0, 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
INSERT INTO prd_spec_value (value_no, dim_no, code, label, numeric_value, numeric_unit, aliases, scope, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('SV_VOLUME_V220ML', 'SD_VOLUME', 'V220ML', '220ml', 220, 'ml', NULL, 'PLATFORM', 220, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V240ML', 'SD_VOLUME', 'V240ML', '240ml', 240, 'ml', NULL, 'PLATFORM', 240, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V300ML', 'SD_VOLUME', 'V300ML', '300ml', 300, 'ml', NULL, 'PLATFORM', 300, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V350ML', 'SD_VOLUME', 'V350ML', '350ml', 350, 'ml', NULL, 'PLATFORM', 350, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V450ML', 'SD_VOLUME', 'V450ML', '450ml', 450, 'ml', NULL, 'PLATFORM', 450, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V480ML', 'SD_VOLUME', 'V480ML', '480ml', 480, 'ml', NULL, 'PLATFORM', 480, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V550ML', 'SD_VOLUME', 'V550ML', '550ml', 550, 'ml', NULL, 'PLATFORM', 550, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V560ML', 'SD_VOLUME', 'V560ML', '560ml', 560, 'ml', NULL, 'PLATFORM', 560, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V600ML', 'SD_VOLUME', 'V600ML', '600ml', 600, 'ml', NULL, 'PLATFORM', 600, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V1900ML', 'SD_VOLUME', 'V1900ML', '1.9L', 1900, 'ml', NULL, 'PLATFORM', 1900, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W15G', 'SD_WEIGHT', 'W15G', '15g', 15, 'g', NULL, 'PLATFORM', 15, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W20G', 'SD_WEIGHT', 'W20G', '20g', 20, 'g', NULL, 'PLATFORM', 20, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W21G', 'SD_WEIGHT', 'W21G', '21g', 21, 'g', NULL, 'PLATFORM', 21, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W30G', 'SD_WEIGHT', 'W30G', '30g', 30, 'g', NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W35G', 'SD_WEIGHT', 'W35G', '35g', 35, 'g', NULL, 'PLATFORM', 35, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W36G', 'SD_WEIGHT', 'W36G', '36g', 36, 'g', NULL, 'PLATFORM', 36, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W45G', 'SD_WEIGHT', 'W45G', '45g', 45, 'g', NULL, 'PLATFORM', 45, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W55G', 'SD_WEIGHT', 'W55G', '55g', 55, 'g', NULL, 'PLATFORM', 55, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W60G', 'SD_WEIGHT', 'W60G', '60g', 60, 'g', NULL, 'PLATFORM', 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W70G', 'SD_WEIGHT', 'W70G', '70g', 70, 'g', NULL, 'PLATFORM', 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W80G', 'SD_WEIGHT', 'W80G', '80g', 80, 'g', NULL, 'PLATFORM', 80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W90G', 'SD_WEIGHT', 'W90G', '90g', 90, 'g', NULL, 'PLATFORM', 90, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W104G', 'SD_WEIGHT', 'W104G', '104g', 104, 'g', NULL, 'PLATFORM', 104, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W105G', 'SD_WEIGHT', 'W105G', '105g', 105, 'g', NULL, 'PLATFORM', 105, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W106G', 'SD_WEIGHT', 'W106G', '106g', 106, 'g', NULL, 'PLATFORM', 106, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W108G', 'SD_WEIGHT', 'W108G', '108g', 108, 'g', NULL, 'PLATFORM', 108, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W115G', 'SD_WEIGHT', 'W115G', '115g', 115, 'g', NULL, 'PLATFORM', 115, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W116G', 'SD_WEIGHT', 'W116G', '116g', 116, 'g', NULL, 'PLATFORM', 116, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W120G', 'SD_WEIGHT', 'W120G', '120g', 120, 'g', NULL, 'PLATFORM', 120, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W135G', 'SD_WEIGHT', 'W135G', '135g', 135, 'g', NULL, 'PLATFORM', 135, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W150G', 'SD_WEIGHT', 'W150G', '150g', 150, 'g', NULL, 'PLATFORM', 150, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W168G', 'SD_WEIGHT', 'W168G', '168g', 168, 'g', NULL, 'PLATFORM', 168, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W180G', 'SD_WEIGHT', 'W180G', '180g', 180, 'g', NULL, 'PLATFORM', 180, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W185G', 'SD_WEIGHT', 'W185G', '185g', 185, 'g', NULL, 'PLATFORM', 185, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W210G', 'SD_WEIGHT', 'W210G', '210g', 210, 'g', NULL, 'PLATFORM', 210, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W220G', 'SD_WEIGHT', 'W220G', '220g', 220, 'g', NULL, 'PLATFORM', 220, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W230G', 'SD_WEIGHT', 'W230G', '230g', 230, 'g', NULL, 'PLATFORM', 230, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W260G', 'SD_WEIGHT', 'W260G', '260g', 260, 'g', NULL, 'PLATFORM', 260, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W300G', 'SD_WEIGHT', 'W300G', '300g', 300, 'g', NULL, 'PLATFORM', 300, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W320G', 'SD_WEIGHT', 'W320G', '320g', 320, 'g', NULL, 'PLATFORM', 320, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W330G', 'SD_WEIGHT', 'W330G', '330g', 330, 'g', NULL, 'PLATFORM', 330, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W340G', 'SD_WEIGHT', 'W340G', '340g', 340, 'g', NULL, 'PLATFORM', 340, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W350G', 'SD_WEIGHT', 'W350G', '350g', 350, 'g', NULL, 'PLATFORM', 350, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W360G', 'SD_WEIGHT', 'W360G', '360g', 360, 'g', NULL, 'PLATFORM', 360, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W400G', 'SD_WEIGHT', 'W400G', '400g', 400, 'g', NULL, 'PLATFORM', 400, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W450G', 'SD_WEIGHT', 'W450G', '450g', 450, 'g', NULL, 'PLATFORM', 450, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W510G', 'SD_WEIGHT', 'W510G', '510g', 510, 'g', NULL, 'PLATFORM', 510, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W600G', 'SD_WEIGHT', 'W600G', '600g', 600, 'g', NULL, 'PLATFORM', 600, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W700G', 'SD_WEIGHT', 'W700G', '700g', 700, 'g', NULL, 'PLATFORM', 700, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_WEIGHT_W800G', 'SD_WEIGHT', 'W800G', '800g', 800, 'g', NULL, 'PLATFORM', 800, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
INSERT INTO prd_spu_std (std_no, category_no, title, subtitle, cover, images, spec_groups, keywords, barcode, source, status, ref_count, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted) VALUES
  ('STD_OFF_6921168509256', 'CAT730', '农夫山泉', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["550ml"], "optionCodes": ["V550ML"]}]', '农夫山泉', '6921168509256', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902083881085', 'CAT180', '娃哈哈 AD 钙奶原味', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["220ml"], "optionCodes": ["V220ML"]}]', 'Wahaha', '6902083881085', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6923644266066', 'CAT180', '纯牛奶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', '特仑苏', '6923644266066', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6928804010114', 'CAT730', '0度无糖可乐', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '可口可乐', '6928804010114', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6928804011142', 'CAT730', '可口可乐', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["330ml"], "optionCodes": ["V330ML"]}]', 'Coca-Cola', '6928804011142', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6907992507095', 'CAT180', '金典', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', '伊利', '6907992507095', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921168520015', 'CAT730', '农夫山泉 饮用天然水', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["1.5L"], "optionCodes": ["V1500ML"]}]', '农夫山泉', '6921168520015', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6917878044729', 'CAT730', '雀巢咖啡', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["15g"], "optionCodes": ["W15G"]}]', 'Nescafé', '6917878044729', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6924743919242', 'CAT730', '墨西哥鸡汁番茄味薯片', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["70g"], "optionCodes": ["W70G"]}]', 'Lay''s', '6924743919242', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6901939621738', 'CAT730', '怡泉苏打水（无糖）', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["330ml"], "optionCodes": ["V330ML"]}]', 'Schweppes', '6901939621738', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6907992500942', 'CAT180', '伊利纯牛奶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["240ml"], "optionCodes": ["V240ML"]}]', '伊利', '6907992500942', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6907992100272', 'CAT180', '伊利', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', NULL, '6907992100272', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6954767470573', 'CAT730', '冰露', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["550ml"], "optionCodes": ["V550ML"]}]', '可口可乐', '6954767470573', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6920152460061', 'CAT760', '红烧牛肉面', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["105g"], "optionCodes": ["W105G"]}]', '康师傅', '6920152460061', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6903473100014', 'CAT730', '醇品速溶咖啡', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["50g"], "optionCodes": ["W50G"]}]', '雀巢咖啡', '6903473100014', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921168560509', 'CAT730', '农夫山泉 饮用纯净水', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '农夫山泉', '6921168560509', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921168593569', 'CAT730', '农夫山泉 茶π果味茶饮料 蜜桃乌龙茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '农夫山泉', '6921168593569', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6928804013672', 'CAT730', '冰露纯悦', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["550ml"], "optionCodes": ["V550ML"]}]', '可口可乐', '6928804013672', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6954767410494', 'CAT730', '可口可乐', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["200ml"], "optionCodes": ["V200ML"]}]', 'coca', '6954767410494', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6972020770130', 'CAT730', '北冰洋橙汁汽水', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["330ml"], "optionCodes": ["V330ML"]}]', 'Arctic Ocean', '6972020770130', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6920459902387', 'CAT730', '冰红茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', '康师傅', '6920459902387', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6909548098880', 'CAT730', '冬瓜汁饮料', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', '深晖', '6909548098880', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6916196410414', 'CAT180', '李子园甜牛奶饮品', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["450ml"], "optionCodes": ["V450ML"]}]', 'LIZIYUAN', '6916196410414', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6976046843311', 'CAT730', '苹果醋果味饮料', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["300ml"], "optionCodes": ["V300ML"]}]', NULL, '6976046843311', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921351120305', 'CAT720', '噜咪啦原切马铃薯片(云贵土豆原味)', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["50g"], "optionCodes": ["W50G"]}]', '云贵薯片', '6921351120305', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6937003704014', 'CAT730', '元气森林气泡水橙子味', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["480ml"], "optionCodes": ["V480ML"]}]', '元气森林', '6937003704014', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6948939681928', 'CAT730', '蜂蜜琥珀核桃仁', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["100g"], "optionCodes": ["W100G"]}]', '百草味', '6948939681928', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6948939696106', 'CAT730', '紫皮腰果', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["100g"], "optionCodes": ["W100G"]}]', '百草味', '6948939696106', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6954767412573', 'CAT150', '可口可乐', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["550ml"], "optionCodes": ["V550ML"]}]', '可口可乐', '6954767412573', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6943949415174', 'CAT720', '藕片', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["30g"], "optionCodes": ["W30G"]}]', '天之湘', '6943949415174', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6933568041649', 'CAT730', '混合坚果', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["450g"], "optionCodes": ["W450G"]}]', NULL, '6933568041649', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6950955909610', 'CAT720', '蜂蜜云朵蛋糕', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["50g"], "optionCodes": ["W50G"]}]', NULL, '6950955909610', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6922577790068', 'CAT180', '简醇', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["100g"], "optionCodes": ["W100G"]}]', '君乐宝', '6922577790068', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6974854978010', 'CAT180', '12度浓醇豆浆', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["1L"], "optionCodes": ["V1L"]}]', '七鲜', '6974854978010', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6932512600369', 'CAT150', '菠萝啤果味饮料', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["330ml"], "optionCodes": ["V330ML"]}]', 'Guang''s', '6932512600369', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6970902470055', 'CAT710', '麻辣红油', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["350ml"], "optionCodes": ["V350ML"]}]', 'Absolute Yummy', '6970902470055', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6924743924116', 'CAT720', '大波浪薯片香辣烤鸡翅味', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["135g"], "optionCodes": ["W135G"]}]', 'Lay''s', '6924743924116', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6931369257108', 'CAT730', '海藻胶体', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["90g"], "optionCodes": ["W90G"]}]', '浪花', '6931369257108', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6930755610138', 'CAT730', '瓜子', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["400g"], "optionCodes": ["W400G"]}]', '老街口', '6930755610138', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6922577752189', 'CAT180', '优致牧场', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', '君乐宝', '6922577752189', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6954767417684', 'CAT730', '可口可乐', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["330ml"], "optionCodes": ["V330ML"]}]', '可口可乐', '6954767417684', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921168558049', 'CAT730', '茉莉花茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '东方树叶', '6921168558049', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6952181800013', 'CAT730', '内酯豆腐', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["330g"], "optionCodes": ["W330G"]}]', '云湖白雪', '6952181800013', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902302888963', 'CAT730', '番茄味土豆片', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["36g"], "optionCodes": ["W36G"]}]', '天使', '6902302888963', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902538004045', 'CAT730', '脉动维生素饮料', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["600ml"], "optionCodes": ["V600ML"]}]', '达能', '6902538004045', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6907592000026', 'CAT730', '大块腐乳', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["340g"], "optionCodes": ["W340G"]}]', 'WANGZHIHE', '6907592000026', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6920459905036', 'CAT730', '包裝飲用水', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["550ml"], "optionCodes": ["V550ML"]}]', '康師傅', '6920459905036', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6928396000364', 'CAT730', '雙趣蛋', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["20g"], "optionCodes": ["W20G"]}]', NULL, '6928396000364', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6932571040281', 'CAT730', '果纤橙汁', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["300ml"], "optionCodes": ["V300ML"]}]', '味全每日', '6932571040281', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6936571910018', 'CAT730', '百事可乐', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["600ml"], "optionCodes": ["V600ML"]}]', '百事', '6936571910018', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6956367338666', 'CAT730', '王老吉凉茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '王老吉', '6956367338666', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6906303888885', 'CAT730', '十三香', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["45g"], "optionCodes": ["W45G"]}]', 'WSY', '6906303888885', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6956511907885', 'CAT730', '手剥巴旦木', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["120g"], "optionCodes": ["W120G"]}]', '三只松鼠', '6956511907885', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6972039816591', 'CAT720', '蒜香青豆', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["210g"], "optionCodes": ["W210G"]}]', '锦玥', '6972039816591', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902083897765', 'CAT180', '营养快线 草莓味', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["500g"], "optionCodes": ["W500G"]}]', 'Wahaha', '6902083897765', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6917878039404', 'CAT730', '睿雅摩卡', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["21g"], "optionCodes": ["W21G"]}]', '雀巢', '6917878039404', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6955150411456', 'CAT180', '有机纯牛奶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', '圣牧', '6955150411456', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6905734301277', 'CAT730', '浪味仙创意花式薯卷（意式番茄味）', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["30g"], "optionCodes": ["W30G"]}]', '旺旺', '6905734301277', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6922848641310', 'CAT730', '黄牌精选红茶', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["100g"], "optionCodes": ["W100G"]}]', '立顿', '6922848641310', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6909995103670', 'CAT730', '小小酥（原味）', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["60g"], "optionCodes": ["W60G"]}]', '旺旺', '6909995103670', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6938866514680', 'CAT180', '高钙牛奶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', '天山雪', '6938866514680', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6938866514871', 'CAT180', '高钙牛奶 10盒', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', '天山雪', '6938866514871', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6942404210064', 'CAT730', '百事可乐', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["330ml"], "optionCodes": ["V330ML"]}]', '百事', '6942404210064', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6946663000152', 'CAT180', '鲜牛奶', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["2kg"], "optionCodes": ["W2KG"]}]', '一鸣', '6946663000152', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6971496201001', 'CAT730', '混合坚果燕麦片', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["500g"], "optionCodes": ["W500G"]}]', '美粥食客', '6971496201001', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6940187266308', 'CAT180', '富硒高钙羊奶粉', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["800g"], "optionCodes": ["W800G"]}]', '蒙牛', '6940187266308', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6930096326262', 'CAT150', '姜汁料酒', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '恒顺', '6930096326262', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6940187265158', 'CAT180', '中老年多维高钙奶粉', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["800g"], "optionCodes": ["W800G"]}]', '蒙牛', '6940187265158', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6922824000643', 'CAT145', '薄盐生抽', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '李锦记', '6922824000643', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902265751731', 'CAT710', '鲜味蚝油', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["700g"], "optionCodes": ["W700G"]}]', '海天', '6902265751731', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6920152497029', 'CAT760', '藤椒牛肉面', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["104g"], "optionCodes": ["W104G"]}]', '康师傅', '6920152497029', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6937962103019', 'CAT760', '红烧牛肉面', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["106g"], "optionCodes": ["W106G"]}]', '康师傅', '6937962103019', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6927756401001', 'CAT740', '榴莲饼', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["220g"], "optionCodes": ["W220G"]}]', '轻食咖', '6927756401001', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6970304386886', 'CAT145', '风味豆豉', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["400g"], "optionCodes": ["W400G"]}]', '龙泽', '6970304386886', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6920202888883', 'CAT730', '红牛', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', NULL, '6920202888883', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6911988033413', 'CAT740', '大葡町', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["115g"], "optionCodes": ["W115G"]}]', '达利园', '6911988033413', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6970682380056', 'CAT730', '春雷笋', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["600g"], "optionCodes": ["W600G"]}]', '椒修郎', '6970682380056', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902007506001', 'CAT150', '料酒', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["350ml"], "optionCodes": ["V350ML"]}]', '恒顺', '6902007506001', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6920458835617', 'CAT730', '菊花茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["450ml"], "optionCodes": ["V450ML"]}]', '怡宝', '6920458835617', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6971196385070', 'CAT730', '蜜桃乌龙茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '奈雪的茶', '6971196385070', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6971196385025', 'CAT730', '葡萄乌龙茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '奈雪的茶', '6971196385025', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6907992103051', 'CAT180', '风味发酵乳', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["450g"], "optionCodes": ["W450G"]}]', '伊利', '6907992103051', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6971884850620', 'CAT730', '原味奶昔乳酸菌饮品（果汁型）', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["300g"], "optionCodes": ["W300G"]}]', NULL, '6971884850620', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6938866513607', 'CAT180', '纯牛奶', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["185g"], "optionCodes": ["W185G"]}]', '天山雪', '6938866513607', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6938866540726', 'CAT180', '麦香牛奶', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["185g"], "optionCodes": ["W185G"]}]', '维维', '6938866540726', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6938866510453', 'CAT180', '天原生态纯牛奶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', '天山雪', '6938866510453', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6927344720187', 'CAT730', '红小豆', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["500g"], "optionCodes": ["W500G"]}]', '梦思乡', '6927344720187', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6934665095528', 'CAT180', '双拼果粒 桃气菠菠', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["90g"], "optionCodes": ["W90G"]}]', '蒙牛', '6934665095528', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6920907808612', 'CAT720', '呀土豆', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["70g"], "optionCodes": ["W70G"]}]', '好丽友', '6920907808612', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6974468300016', 'CAT730', '含气果味咖啡饮料（红西柚味）', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["300ml"], "optionCodes": ["V300ML"]}]', '无限波谱', '6974468300016', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6938536200103', 'CAT730', '肉松面包', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["100g"], "optionCodes": ["W100G"]}]', '金马', '6938536200103', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902083891329', 'CAT180', '娃哈哈 营养快线 香草冰淇淋味', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["500g"], "optionCodes": ["W500G"]}]', '娃哈哈', '6902083891329', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6912049351040', 'CAT730', '沙棘原浆', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["50ml"], "optionCodes": ["V50ML"]}]', '维士杰', '6912049351040', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6955150411463', 'CAT180', '有机纯牛奶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["200ml"], "optionCodes": ["V200ML"]}]', '圣牧', '6955150411463', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6959269900450', 'CAT720', '鲜蛋酥', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["400g"], "optionCodes": ["W400G"]}]', '柏味园', '6959269900450', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902088932249', 'CAT730', '茉莉花茶', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["100g"], "optionCodes": ["W100G"]}]', '立顿', '6902088932249', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6911988009852', 'CAT720', '高纤粗粮饼', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["168g"], "optionCodes": ["W168G"]}]', '好吃点', '6911988009852', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6911988000170', 'CAT720', '高纤蔬菜饼', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["168g"], "optionCodes": ["W168G"]}]', '好吃点', '6911988000170', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6972146801022', 'CAT730', '精制刺梨汁', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["50ml"], "optionCodes": ["V50ML"]}]', '羿宫坊', '6972146801022', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6972333776270', 'CAT730', '花式面包', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["70g"], "optionCodes": ["W70G"]}]', '桃李', '6972333776270', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921317600711', 'CAT710', '番茄沙司', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["330g"], "optionCodes": ["W330G"]}]', '味好美', '6921317600711', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6927770903437', 'CAT180', '花都牧场有机纯牛奶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["220ml"], "optionCodes": ["V220ML"]}]', '山花', '6927770903437', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6973330082548', 'CAT730', '奇亚籽胚芽燕麦片', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["35g"], "optionCodes": ["W35G"]}]', '燕谷坊', '6973330082548', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6901668005854', 'CAT730', 'DL 100%葡萄复合果汁', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["1L"], "optionCodes": ["V1L"]}]', 'OREO', '6901668005854', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6931958063691', 'CAT720', 'QQ桃桃果汁100软糖', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["60g"], "optionCodes": ["W60G"]}]', '旺旺', '6931958063691', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6954767400785', 'CAT730', '阳光 柠檬味茶饮料', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '阳光', '6954767400785', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6931958034592', 'CAT720', '旺仔泡芙（牛奶味）', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["30g"], "optionCodes": ["W30G"]}]', '旺旺', '6931958034592', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6922507005408', 'CAT730', '康师傅包装饮用水', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["1.5kg"], "optionCodes": ["W1500G"]}]', '康师傅', '6922507005408', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6971714701566', 'CAT180', '谷焙奇酸奶沙琪玛', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["350g"], "optionCodes": ["W350G"]}]', '谷焙奇', '6971714701566', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6922507036808', 'CAT730', '康师傅喝开水', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["1.5kg"], "optionCodes": ["W1500G"]}]', '康师傅', '6922507036808', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6934024513007', 'CAT730', '百事纯水乐', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["1.5kg"], "optionCodes": ["W1500G"]}]', '百事', '6934024513007', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6959202303881', 'CAT730', '100% NFC黄桃汁', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["1kg"], "optionCodes": ["W1KG"]}]', '汇多滋', '6959202303881', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6954767430461', 'CAT730', '雪碧纤维+', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["500g"], "optionCodes": ["W500G"]}]', '可口可乐', '6954767430461', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6923404800974', 'CAT730', '就是桃 水蜜桃风味饮料', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', '盼盼', '6923404800974', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6971858720102', 'CAT180', '纯牛乳', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["200g"], "optionCodes": ["W200G"]}]', '认养', '6971858720102', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6901668009784', 'CAT720', '香脆曲奇 浓浓巧克力味', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["510g"], "optionCodes": ["W510G"]}]', '趣多多', '6901668009784', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6917878078861', 'CAT730', '宝路薄荷糖', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["70g"], "optionCodes": ["W70G"]}]', '雀巢', '6917878078861', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6906839618604', 'CAT730', '维多粒果冻爽(荔枝味)', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["150g"], "optionCodes": ["W150G"]}]', '旺旺', '6906839618604', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6939729902255', 'CAT730', '凉白开', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["550ml"], "optionCodes": ["V550ML"]}]', '今麦郎', '6939729902255', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6934665095511', 'CAT180', '双拼果粒 莓莓满满', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["90g"], "optionCodes": ["W90G"]}]', '蒙牛', '6934665095511', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902083922658', 'CAT180', '娃哈哈 AD钙 AD钙奶饮料', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["450ml"], "optionCodes": ["V450ML"]}]', '娃哈哈', '6902083922658', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6972434755648', 'CAT720', '诺特兰得 血橙复合B族维生素咀嚼片 运动营养食品 耐力类', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["36g"], "optionCodes": ["W36G"]}]', '诺特兰德', '6972434755648', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6971196385162', 'CAT730', '奈雪果茶 青提香乌龙茶 果汁茶饮料', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["450ml"], "optionCodes": ["V450ML"]}]', '奈雪的茶', '6971196385162', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6914522030091', 'CAT730', 'DL酸奶水果燕麦脆', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["320g"], "optionCodes": ["W320G"]}]', 'DL', '6914522030091', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6925303789862', 'CAT730', '雅哈柠檬水 柠檬味饮料', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["1L"], "optionCodes": ["V1L"]}]', 'Uni-President', '6925303789862', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6977590340011', 'CAT720', '老味糕点', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["220g"], "optionCodes": ["W220G"]}]', '北方威力发', '6977590340011', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6920181525014', 'CAT730', '盐汽水', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["600ml"], "optionCodes": ["V600ML"]}]', '延中', '6920181525014', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6901010117440', 'CAT730', '健力宝', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["560ml"], "optionCodes": ["V560ML"]}]', '健力宝', '6901010117440', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6901939621257', 'CAT730', '无糖可口可乐汽水', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["330ml"], "optionCodes": ["V330ML"]}]', 'Coca cola', '6901939621257', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6901939691601', 'CAT730', '可口可乐汽水', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["2L"], "optionCodes": ["V2L"]}]', 'Coca Cola', '6901939691601', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6903396008718', 'CAT720', '钙维C脆皮奶球糖果', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["108g"], "optionCodes": ["W108G"]}]', '奇峰', '6903396008718', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6910160312377', 'CAT730', '丘比沙拉酱', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["200g"], "optionCodes": ["W200G"]}]', '丘比', '6910160312377', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6920934800863', 'CAT730', '吕梁野山坡生榨沙棘', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["300ml"], "optionCodes": ["V300ML"]}]', '吕梁野山坡', '6920934800863', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921168509270', 'CAT730', '饮用天然水', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["4L"], "optionCodes": ["V4L"]}]', '农夫山泉', '6921168509270', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921417900018', 'CAT730', '天然矿泉水', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["330ml"], "optionCodes": ["V330ML"]}]', '万悦', '6921417900018', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6922824075016', 'CAT145', '精选老抽', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', 'Lee Kum Kee', '6922824075016', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6924743915428', 'CAT730', '墨西哥鸡汁番茄味薯片', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["45g"], "optionCodes": ["W45G"]}]', 'Lay''s(乐事)', '6924743915428', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6934158356785', 'CAT760', '康师傅红烧排骨面', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["105g"], "optionCodes": ["W105G"]}]', '康师傅', '6934158356785', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6938295497370', 'CAT150', 'FANTASTIC 翻篇儿', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["330ml"], "optionCodes": ["V330ML"]}]', '龙精酿', '6938295497370', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6941734101356', 'CAT730', '酷美苹果醋', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["480ml"], "optionCodes": ["V480ML"]}]', '酷美', '6941734101356', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6944839902385', 'CAT730', '康师傅冰红茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', '康师傅', '6944839902385', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6944839956548', 'CAT730', '康师傅鲜果橙', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', '康师傅', '6944839956548', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6949352201014', 'CAT150', '雪花冰酷啤酒', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["330ml"], "optionCodes": ["V330ML"]}]', '雪花', '6949352201014', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6951444610017', 'CAT730', '金卡能量6小时', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["450ml"], "optionCodes": ["V450ML"]}]', '点趣', '6951444610017', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6953240737677', 'CAT720', '良品铺子 椰子脆片(coconut chips)', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["35g"], "optionCodes": ["W35G"]}]', '良品铺子', '6953240737677', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6954767415772', 'CAT730', '可口可乐', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["600ml"], "optionCodes": ["V600ML"]}]', '可口可乐', '6954767415772', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921204802204', 'CAT145', '豆瓣酱', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["300g"], "optionCodes": ["W300G"]}]', '葱伴侣', '6921204802204', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6901035601870', 'CAT150', '纯生', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', 'tsingtao', '6901035601870', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902265150015', 'CAT145', '味极鲜酱油', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["750ml"], "optionCodes": ["V750ML"]}]', 'Haday', '6902265150015', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902265450115', 'CAT710', '9度纯酿米醋', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["450ml"], "optionCodes": ["V450ML"]}]', 'haday', '6902265450115', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
INSERT INTO prd_spu_std (std_no, category_no, title, subtitle, cover, images, spec_groups, keywords, barcode, source, status, ref_count, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted) VALUES
  ('STD_OFF_6901668008176', 'CAT720', '奥利奥夹心饼干', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["116g"], "optionCodes": ["W116G"]}]', '奥利奥', '6901668008176', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6908471004470', 'CAT180', '六个核桃', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["240ml"], "optionCodes": ["V240ML"]}]', '养元', '6908471004470', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6924187846180', 'CAT730', '原香瓜子', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["260g"], "optionCodes": ["W260G"]}]', 'Cha Cha', '6924187846180', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6942656601269', 'CAT730', '青梅绿茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '达利园', '6942656601269', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6922507005019', 'CAT730', '康师傅 冰红茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '康师傅', '6922507005019', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6924673500176', 'CAT730', '主食面包', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["450g"], "optionCodes": ["W450G"]}]', '桃李', '6924673500176', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6901010119673', 'CAT730', '健力宝', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', NULL, '6901010119673', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902007505264', 'CAT710', '镇江香醋', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["550ml"], "optionCodes": ["V550ML"]}]', '恒顺', '6902007505264', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902265360018', 'CAT710', '海天上等蚝油', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["700g"], "optionCodes": ["W700G"]}]', '海天', '6902265360018', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6918768951684', 'CAT720', '涉素牛肉', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["80g"], "optionCodes": ["W80G"]}]', 'ZHEN XIANG', '6918768951684', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921804700054', 'CAT730', '老干妈', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["210g"], "optionCodes": ["W210G"]}]', 'LAOGANMA', '6921804700054', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6922877721021', 'CAT730', '頂好牌花生醬', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["510g"], "optionCodes": ["W510G"]}]', 'Skippy', '6922877721021', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6923644242909', 'CAT180', '蒙牛高鈣低脂奶類飲品', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', '蒙牛', '6923644242909', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6947593015247', 'CAT145', '金裝鮮味生抽', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', 'Pearl River Bridge', '6947593015247', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6950384900172', 'CAT730', '阿胶枣', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["350g"], "optionCodes": ["W350G"]}]', NULL, '6950384900172', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921294392685', 'CAT730', '酸梅汤', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', 'Mr Kon', '6921294392685', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6970584260975', 'CAT730', '中国绿茶', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["200g"], "optionCodes": ["W200G"]}]', 'Tour de dragon', '6970584260975', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921555507346', 'CAT760', 'Noodles 今麦郎 鸡蛋面', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["1kg"], "optionCodes": ["W1KG"]}]', NULL, '6921555507346', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6923818881323', 'CAT720', '迷你费南雪蛋糕（Petits fours financiers）', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["500g"], "optionCodes": ["W500G"]}]', NULL, '6923818881323', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6920548862097', 'CAT720', '旺旺旺仔QQ糖水蜜桃味', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["70g"], "optionCodes": ["W70G"]}]', '旺仔', '6920548862097', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921665709012', 'CAT730', '放心油条', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["450g"], "optionCodes": ["W450G"]}]', 'Synear', '6921665709012', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6971090271868', 'CAT730', '蒜香面包脆', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["100g"], "optionCodes": ["W100G"]}]', 'Carrefour', '6971090271868', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6970399920156', 'CAT730', '果の每日茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '元气森林', '6970399920156', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6948505406047', 'CAT730', '麻辣花生', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["210g"], "optionCodes": ["W210G"]}]', '黄飞红', '6948505406047', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6920459905166', 'CAT730', '康师傅绿茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', 'Master Kong', '6920459905166', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6948939611178', 'CAT720', '百草味抹茶味麻薯', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["210g"], "optionCodes": ["W210G"]}]', '百草味', '6948939611178', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6924743915763', 'CAT730', 'Lay''s 乐事 无限 忠于原味', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["104g"], "optionCodes": ["W104G"]}]', 'Lay''s', '6924743915763', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6901845043594', 'CAT720', 'Pocky 百奇', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["55g"], "optionCodes": ["W55G"]}]', 'Glico', '6901845043594', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902265470267', 'CAT710', '黄豆酱', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["340g"], "optionCodes": ["W340G"]}]', 'Haday', '6902265470267', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6920459998434', 'CAT730', '茉莉清茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '康师傅', '6920459998434', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921804701563', 'CAT730', '香辣脆油辣椒', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["700g"], "optionCodes": ["W700G"]}]', 'Laoganma', '6921804701563', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6920238011118', 'CAT760', 'Kimchi 辣白菜 ramen', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["120g"], "optionCodes": ["W120G"]}]', 'Nongshim', '6920238011118', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6906303000164', 'CAT730', '麻辣鲜调味料', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["60g"], "optionCodes": ["W60G"]}]', '王守义', '6906303000164', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6924878900573', 'CAT730', '麻辣素牛肉', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["108g"], "optionCodes": ["W108G"]}]', 'wu xian zhai', '6924878900573', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6901845045086', 'CAT720', '百奇 水果粒粒', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["45g"], "optionCodes": ["W45G"]}]', 'Glico', '6901845045086', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6903431110017', 'CAT150', '汾酒', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '杏花村', '6903431110017', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6901035601481', 'CAT150', '啤酒', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', 'Tsingtao', '6901035601481', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6926719110042', 'CAT720', '大白兔奶糖', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["180g"], "optionCodes": ["W180G"]}]', 'White Rabbit', '6926719110042', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6922547663880', 'CAT710', '鲜花椒油', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["200ml"], "optionCodes": ["V200ML"]}]', '川珍', '6922547663880', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6942875500831', 'CAT710', '小茴香', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["30g"], "optionCodes": ["W30G"]}]', '味名源扬', '6942875500831', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6948229369611', 'CAT720', '果丹皮 楂们', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["150g"], "optionCodes": ["W150G"]}]', '劲达', '6948229369611', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6971258742841', 'CAT720', '卫龙魔芋爽香辣素毛肚', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["360g"], "optionCodes": ["W360G"]}]', 'Luohe Pingping Food', '6971258742841', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6971258744357', 'CAT720', '卫龙大面筋辣条', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["106g"], "optionCodes": ["W106G"]}]', '卫龙', '6971258744357', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6920238066019', 'CAT760', '辛辣麵', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["600g"], "optionCodes": ["W600G"]}]', 'Nongshim', '6920238066019', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6924743919228', 'CAT730', '德克萨斯烧烤味', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["70g"], "optionCodes": ["W70G"]}]', 'Lay''s', '6924743919228', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902538005141', 'CAT730', '脉动 桃子口味 维生素饮料', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["600ml"], "optionCodes": ["V600ML"]}]', 'Mizone', '6902538005141', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6932522000906', 'CAT730', '公社山楂片', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["210g"], "optionCodes": ["W210G"]}]', '公社联盟', '6932522000906', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6900171210625', 'CAT145', '鲜味生抽', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["1.9L"], "optionCodes": ["V1900ML"]}]', '海天', '6900171210625', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6926410320214', 'CAT760', '红油宽面', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["115g"], "optionCodes": ["W115G"]}]', 'Hi A’kuan', '6926410320214', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6924743926653', 'CAT730', '香辣小龍蝦味', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["104g"], "optionCodes": ["W104G"]}]', 'Lay''s', '6924743926653', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6971258742797', 'CAT730', '卫龙亲嘴烧麦辣鸡汁味', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["260g"], "optionCodes": ["W260G"]}]', '卫龙', '6971258742797', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902265711209', 'CAT710', '辣黄豆酱', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["800g"], "optionCodes": ["W800G"]}]', '海天', '6902265711209', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6922456805012', 'CAT730', '康师傅冰红茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', 'Master Kong', '6922456805012', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902482001367', 'CAT760', '龙口粉丝', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["500g"], "optionCodes": ["W500G"]}]', 'Guanzhu', '6902482001367', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921294398847', 'CAT730', '低糖绿茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["2L"], "optionCodes": ["V2L"]}]', '康师傅', '6921294398847', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6951445604343', 'CAT730', '三诺葡萄糖补水液 GLUCOSE', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["450ml"], "optionCodes": ["V450ML"]}]', 'Sannuo Sanji Enterprise Group', '6951445604343', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6946054922193', 'CAT710', '蠔油', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["750g"], "optionCodes": ["W750G"]}]', 'Chan Moon Kee', '6946054922193', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6924343526376', 'CAT730', '美式咖啡', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', '牵手', '6924343526376', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6973497201752', 'CAT730', '多多柠檬茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '果子熟了', '6973497201752', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902265504511', 'CAT145', '海天招牌老抽王', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '海天', '6902265504511', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6954769011132', 'CAT730', '五香粉', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["30g"], "optionCodes": ["W30G"]}]', '禾茵', '6954769011132', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6941704411027', 'CAT180', '新希望 雪兰 纯牛奶', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["250g"], "optionCodes": ["W250G"]}]', '新希望', '6941704411027', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6931744233444', 'CAT180', '浓缩纯牛奶', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["180g"], "optionCodes": ["W180G"]}]', '天润', '6931744233444', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6956426020105', 'CAT730', '魔芋贡菜(香辣味)', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["15g"], "optionCodes": ["W15G"]}]', 'moyugocai', '6956426020105', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6927823690024', 'CAT730', '燕麦片', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["350g"], "optionCodes": ["W350G"]}]', '皇室', '6927823690024', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6922833028744', 'CAT180', '佳品 ® 切达芝士球', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["100g"], "optionCodes": ["W100G"]}]', '佳品 ®', '6922833028744', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6920548862998', 'CAT720', '旺仔QQ糖荔枝味', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["70g"], "optionCodes": ["W70G"]}]', '旺仔', '6920548862998', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6970150100872', 'CAT730', '平果桂之竹腐竹', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["250g"], "optionCodes": ["W250G"]}]', NULL, '6970150100872', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6907992514079', 'CAT180', '舒化牛奶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["220ml"], "optionCodes": ["V220ML"]}]', 'Shuhua', '6907992514079', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6937623400204', 'CAT730', '劲仔厚豆干麻辣味', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["21g"], "optionCodes": ["W21G"]}]', '劲仔', '6937623400204', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921294355383', 'CAT730', '劲凉冰红茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["1L"], "optionCodes": ["V1L"]}]', '康师傅', '6921294355383', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6904432800372', 'CAT180', '维他型豆奶粉', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["500g"], "optionCodes": ["W500G"]}]', '维维', '6904432800372', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6924743931350', 'CAT730', '乐事酸辣柠檬凤爪味薯片', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["80g"], "optionCodes": ["W80G"]}]', 'Lay''s', '6924743931350', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6920180210577', 'CAT730', '蘇打水', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["330ml"], "optionCodes": ["V330ML"]}]', '屈臣氏', '6920180210577', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921294389906', 'CAT730', '水蜜桃', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["1L"], "optionCodes": ["V1L"]}]', '康師傅', '6921294389906', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6924743915831', 'CAT720', '无限薯片 黑椒牛排味', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["30g"], "optionCodes": ["W30G"]}]', '乐事', '6924743915831', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6970399922365', 'CAT730', '外星人电解质水荔枝味', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', 'Alienergy', '6970399922365', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6972549660677', 'CAT730', '茉莉乌龙茶饮料', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '三得利', '6972549660677', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6975682480058', 'CAT730', '美汁源 果粒橙 橙汁饮料', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["450ml"], "optionCodes": ["V450ML"]}]', 'Minute Maid', '6975682480058', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6908946291329', 'CAT730', '美年达 干杯 啤酒味风味汽水', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', 'Mirinda', '6908946291329', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6908095930186', 'CAT730', '厦顺 冰红茶 柠檬味调味茶饮料', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', '厦顺', '6908095930186', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6972434756768', 'CAT720', '诺特兰德 钙+维生素D咀嚼片 运动营养零食 耐力类', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["108g"], "optionCodes": ["W108G"]}]', '诺特兰得', '6972434756768', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6975291510849', 'CAT730', '悦益多 果味营养素饮料 小青柠味维生素饮料', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', '悦益多', '6975291510849', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6932394905033', 'CAT730', '康师傅 包装饮用水', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["550ml"], "optionCodes": ["V550ML"]}]', '康师傅', '6932394905033', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6976862280147', 'CAT730', '绿得 甘蔗汁 植物饮料', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["450ml"], "optionCodes": ["V450ML"]}]', '绿得', '6976862280147', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6926892575003', 'CAT730', '雀巢茶萃 柠檬冻红茶果汁茶饮料', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', 'Nestea', '6926892575003', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6911988042873', 'CAT730', '乐虎 维生素能量饮料', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '乐虎', '6911988042873', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6926892510059', 'CAT730', '银鹭纯净水', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["560ml"], "optionCodes": ["V560ML"]}]', '银鹭', '6926892510059', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6935145343108', 'CAT150', 'Rio 微醺Light 乐橘乌龙鸡尾酒', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["330ml"], "optionCodes": ["V330ML"]}]', 'Rio', '6935145343108', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6938866592541', 'CAT730', '维维椰奶西米露', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["320g"], "optionCodes": ["W320G"]}]', '维维', '6938866592541', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921168599592', 'CAT730', '良品铺子鱼肉棒蟹柳味', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["90g"], "optionCodes": ["W90G"]}]', '良品铺子', '6921168599592', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6925678100583', 'CAT145', '金磨坊香辣豆筋', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["90g"], "optionCodes": ["W90G"]}]', '金磨坊', '6925678100583', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6972343076919', 'CAT710', '茄子烧焦酱', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["230g"], "optionCodes": ["W230G"]}]', '川娃子', '6972343076919', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6908946290087', 'CAT730', '百事可乐', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["330ml"], "optionCodes": ["V330ML"]}]', 'Pepsi', '6908946290087', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6914253431426', 'CAT720', '雪丽糍 夹心棉花糖 香橙味', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["100g"], "optionCodes": ["W100G"]}]', NULL, '6914253431426', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902265591917', 'CAT145', '0金标生抽', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '海天', '6902265591917', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6901668054715', 'CAT180', 'mini奥利奥草莓味', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["55g"], "optionCodes": ["W55G"]}]', 'Mondelēz', '6901668054715', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6937003704861', 'CAT730', '特燃生普洱茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '元气森林', '6937003704861', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6928804011128', 'CAT730', '可口可乐', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["300ml"], "optionCodes": ["V300ML"]}]', 'Coca cola', '6928804011128', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6972799491410', 'CAT730', '生椰小拿鐵', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["200g"], "optionCodes": ["W200G"]}]', 'Yili', '6972799491410', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6907992515090', 'CAT180', '安慕希酸奶', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["230g"], "optionCodes": ["W230G"]}]', '安慕希', '6907992515090', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6928758789609', 'CAT180', '乳酸菌乳飲品', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', 'Yakult', '6928758789609', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6976005190463', 'CAT730', '0糖椰汁（植物蛋白飲品）', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["1kg"], "optionCodes": ["W1KG"]}]', 'Jianpai', '6976005190463', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6907992101934', 'CAT180', '風味發酵乳', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["100g"], "optionCodes": ["W100G"]}]', 'yili', '6907992101934', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6974377661703', 'CAT730', '柿饼', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["1L"], "optionCodes": ["V1L"]}]', 'Mei.Z.W.', '6974377661703', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6973497202346', 'CAT730', '栀栀乌龙茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '果子熟了', '6973497202346', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6977245210379', 'CAT730', '大红袍花椒', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["20g"], "optionCodes": ["W20G"]}]', 'Yuhe Foods', '6977245210379', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6911316600034', 'CAT720', '阿尔卑斯 田园草莓牛奶味硬糖', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["150g"], "optionCodes": ["W150G"]}]', '不凡帝范梅勒糖果(中国)有限公司', '6911316600034', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921204896104', 'CAT145', '大酱', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["180g"], "optionCodes": ["W180G"]}]', '葱伴侣', '6921204896104', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6974891200006', 'CAT740', '味源八寶粥', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["360g"], "optionCodes": ["W360G"]}]', '味源', '6974891200006', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6924882497106', 'CAT730', '百事可乐 罐装 330ml', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["330ml"], "optionCodes": ["V330ML"]}]', '百事', '6924882497106', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6941410700637', 'CAT720', '香弹面筋卷', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["135g"], "optionCodes": ["W135G"]}]', '百草味', '6941410700637', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6922017035001', 'CAT720', '爆浆 串串山楂', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["108g"], "optionCodes": ["W108G"]}]', 'Osay', '6922017035001', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6900731805711', 'CAT720', '香葱饼干', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["350g"], "optionCodes": ["W350G"]}]', '大润发', '6900731805711', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6974906991851', 'CAT730', '苦瓜檸檬茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["450ml"], "optionCodes": ["V450ML"]}]', '輕輕果蔬茶', '6974906991851', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6974800842075', 'CAT730', '大窑嘉宾', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["330ml"], "optionCodes": ["V330ML"]}]', 'Dayao', '6974800842075', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6976140630190', 'CAT730', '李海龙 二八酱', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["250g"], "optionCodes": ["W250G"]}]', '沈阳市李海龙餐饮管理有限公司', '6976140630190', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6941084202864', 'CAT730', '珍醇 纯芝麻酱', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["300g"], "optionCodes": ["W300G"]}]', '保定市冠香居食品有限公司', '6941084202864', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6905320843211', 'CAT720', '奶油椰蓉月饼', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["100g"], "optionCodes": ["W100G"]}]', '杏花楼', '6905320843211', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6905320034503', 'CAT720', '绿豆蓉月饼', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["100g"], "optionCodes": ["W100G"]}]', '杏花楼', '6905320034503', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6905320034565', 'CAT720', '五仁月饼', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["100g"], "optionCodes": ["W100G"]}]', '杏花楼', '6905320034565', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6954759008814', 'CAT730', '白胡椒粉', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["30g"], "optionCodes": ["W30G"]}]', '双葉産業株式会社', '6954759008814', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6954769010029', 'CAT730', '茴香', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["50g"], "optionCodes": ["W50G"]}]', '禾茵', '6954769010029', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6933266607185', 'CAT730', '百事可乐', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["330ml"], "optionCodes": ["V330ML"]}]', '百事', '6933266607185', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6970153485518', 'CAT720', '软乎乎奶角蛋糕', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["300g"], "optionCodes": ["W300G"]}]', '七鲜烘焙', '6970153485518', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902265010012', 'CAT145', '0金标生抽', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["100ml"], "optionCodes": ["V100ML"]}]', '海天', '6902265010012', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6956786500590', 'CAT720', '烈火牛肉', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["20g"], "optionCodes": ["W20G"]}]', '毛俊男', '6956786500590', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6947670107735', 'CAT710', '0%添加甜面酱', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["450g"], "optionCodes": ["W450G"]}]', '佐香园', '6947670107735', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6951454426110', 'CAT760', '奶油芝士火鸡面(油炸型方便面)', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["116g"], "optionCodes": ["W116G"]}]', 'Tong Wan Fu', '6951454426110', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6973775710327', 'CAT730', '橙汁', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["1L"], "optionCodes": ["V1L"]}]', '碧林', '6973775710327', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902265240037', 'CAT145', '老抽王', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["1.9L"], "optionCodes": ["V1900ML"]}]', 'Haday', '6902265240037', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902265752516', 'CAT710', '金标生抽', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["750ml"], "optionCodes": ["V750ML"]}]', '海天', '6902265752516', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6902902011990', 'CAT710', '宴会料酒', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', '厨邦', '6902902011990', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6922824002050', 'CAT710', '黄豆酱', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["800g"], "optionCodes": ["W800G"]}]', '李锦记', '6922824002050', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6943515520035', 'CAT730', '麦纯精制粉', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["5kg"], "optionCodes": ["W5KG"]}]', '香雪', '6943515520035', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6948854712684', 'CAT730', '即溶普洱茶珍', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["100g"], "optionCodes": ["W100G"]}]', '帝泊洱', '6948854712684', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6927756701316', 'CAT730', '梅菜笋丝', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["70g"], "optionCodes": ["W70G"]}]', '宝食', '6927756701316', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921168595150', 'CAT730', '茶π果味茶饮料 柠檬红茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', '农夫山泉', '6921168595150', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6921168595143', 'CAT730', '茶π果味茶饮料 柠檬红茶', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["250ml"], "optionCodes": ["V250ML"]}]', '农夫山泉', '6921168595143', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6918149912310', 'CAT710', '剁辣椒', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["250g"], "optionCodes": ["W250G"]}]', '丹丹', '6918149912310', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6926104989314', 'CAT730', '果缤沙可吸果冻(香橙味)(Cinnamoroll 版)', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["80g"], "optionCodes": ["W80G"]}]', '盐津铺子', '6926104989314', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6977279640036', 'CAT730', '茉莉绿茶饮料（无糖）', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["500ml"], "optionCodes": ["V500ML"]}]', 'Lawson', '6977279640036', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6917878362199', 'CAT720', '脆脆鲨芝士味威化饼干36g', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["36g"], "optionCodes": ["W36G"]}]', 'Nestlé', '6917878362199', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6926664706048', 'CAT720', '三牛万年青饼干', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["20g"], "optionCodes": ["W20G"]}]', 'Sanniu', '6926664706048', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6959752101678', 'CAT720', '鳕鱼烤鱼片', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["250g"], "optionCodes": ["W250G"]}]', '老先生', '6959752101678', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6975682483042', 'CAT730', '汁汁桃桃桃汁饮料', NULL, NULL, NULL, '[{"name": "容量", "templateNo": "SD_VOLUME", "options": ["450ml"], "optionCodes": ["V450ML"]}]', 'Minute Maid', '6975682483042', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
  ('STD_OFF_6949682815974', 'CAT730', '兵團紅棗王', NULL, NULL, NULL, '[{"name": "重量", "templateNo": "SD_WEIGHT", "options": ["600g"], "optionCodes": ["W600G"]}]', '金谷園', '6949682815974', 'OFF', 'ARCHIVED', 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
UPDATE sys_merchant_plan_def SET store_quota = 3,  updated_at = NOW() WHERE plan_code = 'FREE';
UPDATE sys_merchant_plan_def SET store_quota = 10, updated_at = NOW() WHERE plan_code = 'PRO';
UPDATE sys_merchant_plan_def SET store_quota = 30, updated_at = NOW() WHERE plan_code = 'CHAIN';
UPDATE mch_entity_plan SET store_quota = 3,  updated_at = NOW() WHERE plan_code = 'FREE'  AND store_quota = 1;
UPDATE mch_entity_plan SET store_quota = 10, updated_at = NOW() WHERE plan_code = 'PRO'   AND store_quota = 3;
UPDATE mch_entity_plan SET store_quota = 30, updated_at = NOW() WHERE plan_code = 'CHAIN' AND store_quota = 10;
INSERT INTO prd_spec_value (value_no, dim_no, code, label, numeric_value, numeric_unit, aliases, scope, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('SV_COUNT_C8', 'SD_COUNT', 'C8', '8件装', 8, NULL, '["8只", "8只装", "8片", "8片装", "8盒", "8盒装", "8袋", "8袋装", "8份", "8份装", "8支", "8支装", "8枚", "8枚装", "8个", "8个装", "8包", "8包装", "8卷", "8卷装", "8条", "8条装", "8粒", "8粒装", "8颗", "8颗装", "8根", "8根装", "8双", "8双装", "8块", "8块装", "8瓶", "8瓶装", "8罐", "8罐装", "8杯", "8杯装", "8听", "8听装", "8件"]', 'PLATFORM', 80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COUNT_C9', 'SD_COUNT', 'C9', '9件装', 9, NULL, '["9只", "9只装", "9片", "9片装", "9盒", "9盒装", "9袋", "9袋装", "9份", "9份装", "9支", "9支装", "9枚", "9枚装", "9个", "9个装", "9包", "9包装", "9卷", "9卷装", "9条", "9条装", "9粒", "9粒装", "9颗", "9颗装", "9根", "9根装", "9双", "9双装", "9块", "9块装", "9瓶", "9瓶装", "9罐", "9罐装", "9杯", "9杯装", "9听", "9听装", "9件"]', 'PLATFORM', 90, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COUNT_C20', 'SD_COUNT', 'C20', '20件装', 20, NULL, '["20只", "20只装", "20片", "20片装", "20盒", "20盒装", "20袋", "20袋装", "20份", "20份装", "20支", "20支装", "20枚", "20枚装", "20个", "20个装", "20包", "20包装", "20卷", "20卷装", "20条", "20条装", "20粒", "20粒装", "20颗", "20颗装", "20根", "20根装", "20双", "20双装", "20块", "20块装", "20瓶", "20瓶装", "20罐", "20罐装", "20杯", "20杯装", "20听", "20听装", "20件"]', 'PLATFORM', 200, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COUNT_C24', 'SD_COUNT', 'C24', '24件装', 24, NULL, '["24只", "24只装", "24片", "24片装", "24盒", "24盒装", "24袋", "24袋装", "24份", "24份装", "24支", "24支装", "24枚", "24枚装", "24个", "24个装", "24包", "24包装", "24卷", "24卷装", "24条", "24条装", "24粒", "24粒装", "24颗", "24颗装", "24根", "24根装", "24双", "24双装", "24块", "24块装", "24瓶", "24瓶装", "24罐", "24罐装", "24杯", "24杯装", "24听", "24听装", "24件"]', 'PLATFORM', 240, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COUNT_C27', 'SD_COUNT', 'C27', '27件装', 27, NULL, '["27只", "27只装", "27片", "27片装", "27盒", "27盒装", "27袋", "27袋装", "27份", "27份装", "27支", "27支装", "27枚", "27枚装", "27个", "27个装", "27包", "27包装", "27卷", "27卷装", "27条", "27条装", "27粒", "27粒装", "27颗", "27颗装", "27根", "27根装", "27双", "27双装", "27块", "27块装", "27瓶", "27瓶装", "27罐", "27罐装", "27杯", "27杯装", "27听", "27听装", "27件"]', 'PLATFORM', 270, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COUNT_C32', 'SD_COUNT', 'C32', '32件装', 32, NULL, '["32只", "32只装", "32片", "32片装", "32盒", "32盒装", "32袋", "32袋装", "32份", "32份装", "32支", "32支装", "32枚", "32枚装", "32个", "32个装", "32包", "32包装", "32卷", "32卷装", "32条", "32条装", "32粒", "32粒装", "32颗", "32颗装", "32根", "32根装", "32双", "32双装", "32块", "32块装", "32瓶", "32瓶装", "32罐", "32罐装", "32杯", "32杯装", "32听", "32听装", "32件"]', 'PLATFORM', 320, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COUNT_C50', 'SD_COUNT', 'C50', '50件装', 50, NULL, '["50只", "50只装", "50片", "50片装", "50盒", "50盒装", "50袋", "50袋装", "50份", "50份装", "50支", "50支装", "50枚", "50枚装", "50个", "50个装", "50包", "50包装", "50卷", "50卷装", "50条", "50条装", "50粒", "50粒装", "50颗", "50颗装", "50根", "50根装", "50双", "50双装", "50块", "50块装", "50瓶", "50瓶装", "50罐", "50罐装", "50杯", "50杯装", "50听", "50听装", "50件"]', 'PLATFORM', 500, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COUNT_C60', 'SD_COUNT', 'C60', '60件装', 60, NULL, '["60只", "60只装", "60片", "60片装", "60盒", "60盒装", "60袋", "60袋装", "60份", "60份装", "60支", "60支装", "60枚", "60枚装", "60个", "60个装", "60包", "60包装", "60卷", "60卷装", "60条", "60条装", "60粒", "60粒装", "60颗", "60颗装", "60根", "60根装", "60双", "60双装", "60块", "60块装", "60瓶", "60瓶装", "60罐", "60罐装", "60杯", "60杯装", "60听", "60听装", "60件"]', 'PLATFORM', 600, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_COUNT_C100', 'SD_COUNT', 'C100', '100件装', 100, NULL, '["100只", "100只装", "100片", "100片装", "100盒", "100盒装", "100袋", "100袋装", "100份", "100份装", "100支", "100支装", "100枚", "100枚装", "100个", "100个装", "100包", "100包装", "100卷", "100卷装", "100条", "100条装", "100粒", "100粒装", "100颗", "100颗装", "100根", "100根装", "100双", "100双装", "100块", "100块装", "100瓶", "100瓶装", "100罐", "100罐装", "100杯", "100杯装", "100听", "100听装", "100件"]', 'PLATFORM', 1000, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
UPDATE prd_spec_value SET aliases = '["单个", "单包", "1件", "1只", "1只装", "1片", "1片装", "1盒", "1盒装", "1袋", "1袋装", "1份", "1份装", "1支", "1支装", "1枚", "1枚装", "1个", "1个装", "1包", "1包装", "1卷", "1卷装", "1条", "1条装", "1粒", "1粒装", "1颗", "1颗装", "1根", "1根装", "1双", "1双装", "1块", "1块装", "1瓶", "1瓶装", "1罐", "1罐装", "1杯", "1杯装", "1听", "1听装", "单台", "单张", "单只", "单支", "单盒", "单袋", "单件", "单份", "单块", "单瓶", "单罐", "单杯", "单条", "单根", "单双", "单片", "单卷", "单粒", "单颗"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C1';
UPDATE prd_spec_value SET aliases = '["2只", "2只装", "2片", "2片装", "2盒", "2盒装", "2袋", "2袋装", "2份", "2份装", "2支", "2支装", "2枚", "2枚装", "2个", "2个装", "2包", "2包装", "2卷", "2卷装", "2条", "2条装", "2粒", "2粒装", "2颗", "2颗装", "2根", "2根装", "2双", "2双装", "2块", "2块装", "2瓶", "2瓶装", "2罐", "2罐装", "2杯", "2杯装", "2听", "2听装", "2件"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C2';
UPDATE prd_spec_value SET aliases = '["3只", "3只装", "3片", "3片装", "3盒", "3盒装", "3袋", "3袋装", "3份", "3份装", "3支", "3支装", "3枚", "3枚装", "3个", "3个装", "3包", "3包装", "3卷", "3卷装", "3条", "3条装", "3粒", "3粒装", "3颗", "3颗装", "3根", "3根装", "3双", "3双装", "3块", "3块装", "3瓶", "3瓶装", "3罐", "3罐装", "3杯", "3杯装", "3听", "3听装", "3件"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C3';
UPDATE prd_spec_value SET aliases = '["4只装", "4只", "4片", "4片装", "4盒", "4盒装", "4袋", "4袋装", "4份", "4份装", "4支", "4支装", "4枚", "4枚装", "4个", "4个装", "4包", "4包装", "4卷", "4卷装", "4条", "4条装", "4粒", "4粒装", "4颗", "4颗装", "4根", "4根装", "4双", "4双装", "4块", "4块装", "4瓶", "4瓶装", "4罐", "4罐装", "4杯", "4杯装", "4听", "4听装", "4件"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C4';
UPDATE prd_spec_value SET aliases = '["5只", "5只装", "5片", "5片装", "5盒", "5盒装", "5袋", "5袋装", "5份", "5份装", "5支", "5支装", "5枚", "5枚装", "5个", "5个装", "5包", "5包装", "5卷", "5卷装", "5条", "5条装", "5粒", "5粒装", "5颗", "5颗装", "5根", "5根装", "5双", "5双装", "5块", "5块装", "5瓶", "5瓶装", "5罐", "5罐装", "5杯", "5杯装", "5听", "5听装", "5件"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C5';
UPDATE prd_spec_value SET aliases = '["6只装", "6卷", "6只", "6片", "6片装", "6盒", "6盒装", "6袋", "6袋装", "6份", "6份装", "6支", "6支装", "6枚", "6枚装", "6个", "6个装", "6包", "6包装", "6卷装", "6条", "6条装", "6粒", "6粒装", "6颗", "6颗装", "6根", "6根装", "6双", "6双装", "6块", "6块装", "6瓶", "6瓶装", "6罐", "6罐装", "6杯", "6杯装", "6听", "6听装", "6件"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C6';
UPDATE prd_spec_value SET aliases = '["10只", "10只装", "10片", "10片装", "10盒", "10盒装", "10袋", "10袋装", "10份", "10份装", "10支", "10支装", "10枚", "10枚装", "10个", "10个装", "10包", "10包装", "10卷", "10卷装", "10条", "10条装", "10粒", "10粒装", "10颗", "10颗装", "10根", "10根装", "10双", "10双装", "10块", "10块装", "10瓶", "10瓶装", "10罐", "10罐装", "10杯", "10杯装", "10听", "10听装", "10件"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C10';
UPDATE prd_spec_value SET aliases = '["12只", "12只装", "12片", "12片装", "12盒", "12盒装", "12袋", "12袋装", "12份", "12份装", "12支", "12支装", "12枚", "12枚装", "12个", "12个装", "12包", "12包装", "12卷", "12卷装", "12条", "12条装", "12粒", "12粒装", "12颗", "12颗装", "12根", "12根装", "12双", "12双装", "12块", "12块装", "12瓶", "12瓶装", "12罐", "12罐装", "12杯", "12杯装", "12听", "12听装", "12件"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C12';
UPDATE prd_spec_value SET aliases = '["二两", "二两装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_WEIGHT_W100G';
UPDATE prd_spec_value SET aliases = '["半斤", "半斤装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_WEIGHT_W250G';
UPDATE prd_spec_value SET aliases = '["1斤", "一斤", "1斤装", "一斤装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_WEIGHT_W500G';
UPDATE prd_spec_value SET aliases = '["1.5斤", "1.5斤装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_WEIGHT_W750G';
UPDATE prd_spec_value SET aliases = '["2斤", "1公斤", "2斤装", "1公斤装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_WEIGHT_W1KG';
UPDATE prd_spec_value SET aliases = '["3斤", "3斤装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_WEIGHT_W1500G';
UPDATE prd_spec_value SET aliases = '["4斤", "4斤装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_WEIGHT_W2KG';
UPDATE prd_spec_value SET aliases = '["5斤", "5斤装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_WEIGHT_W2500G';
UPDATE prd_spec_value SET aliases = '["10斤", "10斤装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_WEIGHT_W5KG';
UPDATE prd_spec_value SET aliases = '["20斤", "20斤装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_WEIGHT_W10KG';
UPDATE prd_spec_value SET aliases = '["1000ml", "1升", "1000ml装", "1升装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_VOLUME_V1L';
UPDATE prd_spec_template
SET name = '数量', updated_at = NOW(), updated_by = 'SYSTEM'
WHERE template_no = 'SPT_SEED_NORMAL_COUNT' AND name = '规格';
INSERT INTO prd_spec_value (value_no, dim_no, code, label, numeric_value, numeric_unit, aliases, scope, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  
  ('SV_CUT_CUTHALF', 'SD_CUT', 'CUTHALF', '半只', NULL, NULL, NULL, 'PLATFORM', 15, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  
  ('SV_WEIGHT_W3KG', 'SD_WEIGHT', 'W3KG', '3kg', 3000, 'g', '["6斤", "6斤装", "3千克"]', 'PLATFORM', 3000, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  
  ('SV_VOLUME_V30ML', 'SD_VOLUME', 'V30ML', '30ml', 30, 'ml', NULL, 'PLATFORM', 30, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V60ML', 'SD_VOLUME', 'V60ML', '60ml', 60, 'ml', NULL, 'PLATFORM', 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_VOLUME_V400ML', 'SD_VOLUME', 'V400ML', '400ml', 400, 'ml', NULL, 'PLATFORM', 400, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  
  ('SV_DURATION_D240', 'SD_DURATION', 'D240', '240分钟', 240, '分钟', '["4小时"]', 'PLATFORM', 240, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
UPDATE prd_spec_value SET aliases = '["单次", "一次"]', updated_at = NOW(), updated_by = 'SYSTEM'
WHERE value_no = 'SV_TIMES_T1' AND aliases IS NULL;
INSERT INTO prd_category_spec (category_no, dim_no, usage_type, is_primary, required, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('CAT110', 'SD_COUNT', NULL, 0, 0, 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),  
  ('CAT140', 'SD_COUNT', NULL, 0, 0, 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),  
  ('CAT140', 'SD_CUT',   NULL, 0, 0, 80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),  
  ('CAT210', 'SD_WEIGHT',NULL, 0, 0, 50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),  
  ('CAT210', 'SD_SIZE',  NULL, 0, 0, 60, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),  
  ('CAT240', 'SD_SIZE',  NULL, 0, 0, 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),  
  ('CAT310', 'SD_COUNT', NULL, 0, 0, 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),  
  ('CAT320', 'SD_COUNT', NULL, 0, 0, 70, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["10斤装", "20斤装"], "optionCodes": ["W5KG", "W10KG"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G0001' AND spec_groups = '[{"name":"规格","options":["10斤装","20斤装"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "容量", "options": ["5L"], "optionCodes": ["V5L"], "templateNo": "SD_VOLUME"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G0002' AND spec_groups = '[{"name":"规格","options":["5L"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["4枚装"], "optionCodes": ["C4"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G0003' AND spec_groups = '[{"name":"规格","options":["4枚装"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["4盒装"], "optionCodes": ["C4"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G0004' AND spec_groups = '[{"name":"规格","options":["4盒装"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "size", "options": ["S", "M"], "optionCodes": ["SZS", "SZM"], "templateNo": "SD_SIZE"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201211530002513' AND spec_groups = '[{"name":"size","options":["S","M"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["4盒"], "optionCodes": ["C4"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201710080013706' AND spec_groups = '[{"name":"规格","options":["4盒"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "处理方式", "options": ["整只"], "optionCodes": ["CUTWHOLE"], "templateNo": "SD_CUT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201710080015357' AND spec_groups = '[{"name":"规格","options":["整只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["400g"], "optionCodes": ["W400G"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201710080017683' AND spec_groups = '[{"name":"规格","options":["400g"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["20粒"], "optionCodes": ["C20"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201710080019327' AND spec_groups = '[{"name":"规格","options":["20粒"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["20片"], "optionCodes": ["C20"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201710080021569' AND spec_groups = '[{"name":"规格","options":["20片"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "时长", "options": ["2小时"], "optionCodes": ["D120"], "templateNo": "SD_DURATION"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201710090023760' AND spec_groups = '[{"name":"规格","options":["2小时"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "时长", "options": ["4小时"], "optionCodes": ["D240"], "templateNo": "SD_DURATION"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201710090025281' AND spec_groups = '[{"name":"规格","options":["4小时"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["24包"], "optionCodes": ["C24"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712510081746' AND spec_groups = '[{"name":"规格","options":["24包"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["27卷"], "optionCodes": ["C27"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712510083032' AND spec_groups = '[{"name":"规格","options":["27卷"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["3kg"], "optionCodes": ["W3KG"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712510085855' AND spec_groups = '[{"name":"规格","options":["3kg"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["500g"], "optionCodes": ["W500G"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712520087792' AND spec_groups = '[{"name":"规格","options":["500g"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["1.5kg"], "optionCodes": ["W1500G"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712520089990' AND spec_groups = '[{"name":"规格","options":["1.5kg"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["4支"], "optionCodes": ["C4"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712520093200' AND spec_groups = '[{"name":"规格","options":["4支"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["4盒"], "optionCodes": ["C4"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712530097277' AND spec_groups = '[{"name":"规格","options":["4盒"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["2斤"], "optionCodes": ["W1KG"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712530105631' AND spec_groups = '[{"name":"规格","options":["2斤"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["2斤"], "optionCodes": ["W1KG"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712540109545' AND spec_groups = '[{"name":"规格","options":["2斤"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["2斤"], "optionCodes": ["W1KG"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712540117867' AND spec_groups = '[{"name":"规格","options":["2斤"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["500g"], "optionCodes": ["W500G"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712550125010' AND spec_groups = '[{"name":"规格","options":["500g"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["500g"], "optionCodes": ["W500G"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712550127694' AND spec_groups = '[{"name":"规格","options":["500g"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["400g"], "optionCodes": ["W400G"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712560129460' AND spec_groups = '[{"name":"规格","options":["400g"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["400g"], "optionCodes": ["W400G"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712560131589' AND spec_groups = '[{"name":"规格","options":["400g"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["500g"], "optionCodes": ["W500G"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712560133080' AND spec_groups = '[{"name":"规格","options":["500g"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["400g"], "optionCodes": ["W400G"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712560135911' AND spec_groups = '[{"name":"规格","options":["400g"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["3颗"], "optionCodes": ["C3"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712560137908' AND spec_groups = '[{"name":"规格","options":["3颗"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["2斤"], "optionCodes": ["W1KG"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712560139072' AND spec_groups = '[{"name":"规格","options":["2斤"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["2斤"], "optionCodes": ["W1KG"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712570141035' AND spec_groups = '[{"name":"规格","options":["2斤"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["2斤"], "optionCodes": ["W1KG"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712570143620' AND spec_groups = '[{"name":"规格","options":["2斤"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["3斤"], "optionCodes": ["W1500G"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712570145946' AND spec_groups = '[{"name":"规格","options":["3斤"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["2斤"], "optionCodes": ["W1KG"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712570147069' AND spec_groups = '[{"name":"规格","options":["2斤"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["500g"], "optionCodes": ["W500G"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712570149571' AND spec_groups = '[{"name":"规格","options":["500g"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["500g"], "optionCodes": ["W500G"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712580151979' AND spec_groups = '[{"name":"规格","options":["500g"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["2斤"], "optionCodes": ["W1KG"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712580153678' AND spec_groups = '[{"name":"规格","options":["2斤"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "处理方式", "options": ["整只"], "optionCodes": ["CUTWHOLE"], "templateNo": "SD_CUT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712580155056' AND spec_groups = '[{"name":"规格","options":["整只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["400g"], "optionCodes": ["W400G"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712580157699' AND spec_groups = '[{"name":"规格","options":["400g"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "处理方式", "options": ["半只"], "optionCodes": ["CUTHALF"], "templateNo": "SD_CUT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712580159059' AND spec_groups = '[{"name":"规格","options":["半只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "处理方式", "options": ["半只"], "optionCodes": ["CUTHALF"], "templateNo": "SD_CUT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712590161876' AND spec_groups = '[{"name":"规格","options":["半只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "处理方式", "options": ["整只"], "optionCodes": ["CUTWHOLE"], "templateNo": "SD_CUT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712590163463' AND spec_groups = '[{"name":"规格","options":["整只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["400g"], "optionCodes": ["W400G"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712590165993' AND spec_groups = '[{"name":"规格","options":["400g"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["4只"], "optionCodes": ["C4"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712590167663' AND spec_groups = '[{"name":"规格","options":["4只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["500g"], "optionCodes": ["W500G"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712590169036' AND spec_groups = '[{"name":"规格","options":["500g"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["2只"], "optionCodes": ["C2"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713000173526' AND spec_groups = '[{"name":"规格","options":["2只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["3份"], "optionCodes": ["C3"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713000175333' AND spec_groups = '[{"name":"规格","options":["3份"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["6只"], "optionCodes": ["C6"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713000177260' AND spec_groups = '[{"name":"规格","options":["6只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["1条"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713000179137' AND spec_groups = '[{"name":"规格","options":["1条"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["400g"], "optionCodes": ["W400G"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713000181691' AND spec_groups = '[{"name":"规格","options":["400g"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "重量", "options": ["400g"], "optionCodes": ["W400G"], "templateNo": "SD_WEIGHT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713000183679' AND spec_groups = '[{"name":"规格","options":["400g"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["12只"], "optionCodes": ["C12"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713010185703' AND spec_groups = '[{"name":"规格","options":["12只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["20只"], "optionCodes": ["C20"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713010187506' AND spec_groups = '[{"name":"规格","options":["20只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["8只"], "optionCodes": ["C8"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713010189613' AND spec_groups = '[{"name":"规格","options":["8只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["6只"], "optionCodes": ["C6"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713010191869' AND spec_groups = '[{"name":"规格","options":["6只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["2份"], "optionCodes": ["C2"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713010193772' AND spec_groups = '[{"name":"规格","options":["2份"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["2份"], "optionCodes": ["C2"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713020195275' AND spec_groups = '[{"name":"规格","options":["2份"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["4根"], "optionCodes": ["C4"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713020203081' AND spec_groups = '[{"name":"规格","options":["4根"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["6只"], "optionCodes": ["C6"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713030205450' AND spec_groups = '[{"name":"规格","options":["6只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["6只"], "optionCodes": ["C6"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713030207257' AND spec_groups = '[{"name":"规格","options":["6只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["1份"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713030209480' AND spec_groups = '[{"name":"规格","options":["1份"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["1份"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713030211272' AND spec_groups = '[{"name":"规格","options":["1份"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["20粒"], "optionCodes": ["C20"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713040215693' AND spec_groups = '[{"name":"规格","options":["20粒"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["20片"], "optionCodes": ["C20"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713040217914' AND spec_groups = '[{"name":"规格","options":["20片"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["24粒"], "optionCodes": ["C24"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713040219341' AND spec_groups = '[{"name":"规格","options":["24粒"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["10袋"], "optionCodes": ["C10"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713050221729' AND spec_groups = '[{"name":"规格","options":["10袋"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["10支"], "optionCodes": ["C10"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713050223384' AND spec_groups = '[{"name":"规格","options":["10支"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["20袋"], "optionCodes": ["C20"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713050225313' AND spec_groups = '[{"name":"规格","options":["20袋"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["9袋"], "optionCodes": ["C9"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713050227004' AND spec_groups = '[{"name":"规格","options":["9袋"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["24粒"], "optionCodes": ["C24"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713050229826' AND spec_groups = '[{"name":"规格","options":["24粒"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["32片"], "optionCodes": ["C32"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713060231945' AND spec_groups = '[{"name":"规格","options":["32片"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["6支"], "optionCodes": ["C6"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713060233387' AND spec_groups = '[{"name":"规格","options":["6支"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["20片"], "optionCodes": ["C20"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713060235638' AND spec_groups = '[{"name":"规格","options":["20片"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["60片"], "optionCodes": ["C60"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713060237008' AND spec_groups = '[{"name":"规格","options":["60片"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["60片"], "optionCodes": ["C60"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713060239767' AND spec_groups = '[{"name":"规格","options":["60片"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["100粒"], "optionCodes": ["C100"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713070241586' AND spec_groups = '[{"name":"规格","options":["100粒"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["20袋"], "optionCodes": ["C20"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713070243474' AND spec_groups = '[{"name":"规格","options":["20袋"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["100片"], "optionCodes": ["C100"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713070245351' AND spec_groups = '[{"name":"规格","options":["100片"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["10片"], "optionCodes": ["C10"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713070247493' AND spec_groups = '[{"name":"规格","options":["10片"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["50支"], "optionCodes": ["C50"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713070249600' AND spec_groups = '[{"name":"规格","options":["50支"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["100片"], "optionCodes": ["C100"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713070251759' AND spec_groups = '[{"name":"规格","options":["100片"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["50只"], "optionCodes": ["C50"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713080253186' AND spec_groups = '[{"name":"规格","options":["50只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["20只"], "optionCodes": ["C20"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713080255450' AND spec_groups = '[{"name":"规格","options":["20只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["单支"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713080257460' AND spec_groups = '[{"name":"规格","options":["单支"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["单支"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713080259161' AND spec_groups = '[{"name":"规格","options":["单支"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["单台"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713080261628' AND spec_groups = '[{"name":"规格","options":["单台"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["套装"], "optionCodes": ["CSET"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713090263493' AND spec_groups = '[{"name":"规格","options":["套装"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "尺码", "options": ["均码"], "optionCodes": ["SZONE"], "templateNo": "SD_SIZE"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713090265859' AND spec_groups = '[{"name":"规格","options":["均码"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["10片"], "optionCodes": ["C10"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713090267427' AND spec_groups = '[{"name":"规格","options":["10片"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["单瓶"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713090269435' AND spec_groups = '[{"name":"规格","options":["单瓶"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "时长", "options": ["2小时"], "optionCodes": ["D120"], "templateNo": "SD_DURATION"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713100275183' AND spec_groups = '[{"name":"规格","options":["2小时"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "时长", "options": ["4小时"], "optionCodes": ["D240"], "templateNo": "SD_DURATION"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713100277129' AND spec_groups = '[{"name":"规格","options":["4小时"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["单台"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713100283070' AND spec_groups = '[{"name":"规格","options":["单台"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["单台"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713110285187' AND spec_groups = '[{"name":"规格","options":["单台"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["单台"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713110287499' AND spec_groups = '[{"name":"规格","options":["单台"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["单台"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713110289894' AND spec_groups = '[{"name":"规格","options":["单台"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["单台"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713110291184' AND spec_groups = '[{"name":"规格","options":["单台"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "时长", "options": ["4小时"], "optionCodes": ["D240"], "templateNo": "SD_DURATION"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713120303069' AND spec_groups = '[{"name":"规格","options":["4小时"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["单只"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713130309628' AND spec_groups = '[{"name":"规格","options":["单只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["单只"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713130311210' AND spec_groups = '[{"name":"规格","options":["单只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["单只"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713130313788' AND spec_groups = '[{"name":"规格","options":["单只"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["单台"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713130315866' AND spec_groups = '[{"name":"规格","options":["单台"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["单台"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713130317037' AND spec_groups = '[{"name":"规格","options":["单台"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["单台"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713140319974' AND spec_groups = '[{"name":"规格","options":["单台"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["单件"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713140329272' AND spec_groups = '[{"name":"规格","options":["单件"]}]';
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W5KG"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK0001' AND market = 'CN' AND option_values = '["10斤装"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W10KG"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK0002' AND market = 'CN' AND option_values = '["20斤装"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_VOLUME_V5L"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK0003' AND market = 'CN' AND option_values = '["5L"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C4"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK0004' AND market = 'CN' AND option_values = '["4枚装"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C4"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK0005' AND market = 'CN' AND option_values = '["4盒装"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_SIZE_SZS"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201217180004603' AND market = 'CN' AND option_values = '["S"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_SIZE_SZM"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201217180005176' AND market = 'CN' AND option_values = '["M"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C4"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201710080014160' AND market = 'CN' AND option_values = '["4盒"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_CUT_CUTWHOLE"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201710080016151' AND market = 'CN' AND option_values = '["整只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W400G"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201710080018986' AND market = 'CN' AND option_values = '["400g"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C20"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201710080020702' AND market = 'CN' AND option_values = '["20粒"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C20"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201710080022917' AND market = 'CN' AND option_values = '["20片"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_DURATION_D120"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201710090024145' AND market = 'CN' AND option_values = '["2小时"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_DURATION_D240"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201710090026978' AND market = 'CN' AND option_values = '["4小时"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C24"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712510082364' AND market = 'CN' AND option_values = '["24包"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C27"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712510084800' AND market = 'CN' AND option_values = '["27卷"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W3KG"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712510086862' AND market = 'CN' AND option_values = '["3kg"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W500G"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712520088377' AND market = 'CN' AND option_values = '["500g"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W1500G"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712520090287' AND market = 'CN' AND option_values = '["1.5kg"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C4"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712520094057' AND market = 'CN' AND option_values = '["4支"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C4"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712530098975' AND market = 'CN' AND option_values = '["4盒"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W1KG"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712530106170' AND market = 'CN' AND option_values = '["2斤"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W1KG"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712540110597' AND market = 'CN' AND option_values = '["2斤"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W1KG"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712550118629' AND market = 'CN' AND option_values = '["2斤"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W500G"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712550126043' AND market = 'CN' AND option_values = '["500g"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W500G"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712550128655' AND market = 'CN' AND option_values = '["500g"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W400G"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712560130175' AND market = 'CN' AND option_values = '["400g"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W400G"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712560132797' AND market = 'CN' AND option_values = '["400g"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W500G"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712560134709' AND market = 'CN' AND option_values = '["500g"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W400G"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712560136972' AND market = 'CN' AND option_values = '["400g"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C3"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712560138896' AND market = 'CN' AND option_values = '["3颗"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W1KG"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712560140500' AND market = 'CN' AND option_values = '["2斤"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W1KG"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712570142798' AND market = 'CN' AND option_values = '["2斤"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W1KG"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712570144130' AND market = 'CN' AND option_values = '["2斤"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W1500G"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712570146459' AND market = 'CN' AND option_values = '["3斤"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W1KG"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712570148790' AND market = 'CN' AND option_values = '["2斤"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W500G"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712570150897' AND market = 'CN' AND option_values = '["500g"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W500G"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712580152695' AND market = 'CN' AND option_values = '["500g"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W1KG"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712580154981' AND market = 'CN' AND option_values = '["2斤"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_CUT_CUTWHOLE"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712580156898' AND market = 'CN' AND option_values = '["整只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W400G"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712580158424' AND market = 'CN' AND option_values = '["400g"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_CUT_CUTHALF"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712580160763' AND market = 'CN' AND option_values = '["半只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_CUT_CUTHALF"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712590162111' AND market = 'CN' AND option_values = '["半只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_CUT_CUTWHOLE"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712590164598' AND market = 'CN' AND option_values = '["整只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W400G"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712590166433' AND market = 'CN' AND option_values = '["400g"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C4"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712590168067' AND market = 'CN' AND option_values = '["4只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W500G"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712590170009' AND market = 'CN' AND option_values = '["500g"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C2"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713000174256' AND market = 'CN' AND option_values = '["2只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C3"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713000176509' AND market = 'CN' AND option_values = '["3份"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C6"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713000178515' AND market = 'CN' AND option_values = '["6只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713000180114' AND market = 'CN' AND option_values = '["1条"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W400G"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713000182842' AND market = 'CN' AND option_values = '["400g"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_WEIGHT_W400G"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713000184146' AND market = 'CN' AND option_values = '["400g"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C12"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713010186061' AND market = 'CN' AND option_values = '["12只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C20"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713010188101' AND market = 'CN' AND option_values = '["20只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C8"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713010190662' AND market = 'CN' AND option_values = '["8只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C6"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713010192587' AND market = 'CN' AND option_values = '["6只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C2"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713010194394' AND market = 'CN' AND option_values = '["2份"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C2"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713020196106' AND market = 'CN' AND option_values = '["2份"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C4"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713020204735' AND market = 'CN' AND option_values = '["4根"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C6"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713030206454' AND market = 'CN' AND option_values = '["6只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C6"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713030208889' AND market = 'CN' AND option_values = '["6只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713030210627' AND market = 'CN' AND option_values = '["1份"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713030212982' AND market = 'CN' AND option_values = '["1份"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C20"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713040216641' AND market = 'CN' AND option_values = '["20粒"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C20"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713040218962' AND market = 'CN' AND option_values = '["20片"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C24"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713050220939' AND market = 'CN' AND option_values = '["24粒"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C10"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713050222488' AND market = 'CN' AND option_values = '["10袋"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C10"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713050224535' AND market = 'CN' AND option_values = '["10支"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C20"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713050226542' AND market = 'CN' AND option_values = '["20袋"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C9"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713050228589' AND market = 'CN' AND option_values = '["9袋"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C24"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713050230502' AND market = 'CN' AND option_values = '["24粒"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C32"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713060232582' AND market = 'CN' AND option_values = '["32片"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C6"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713060234884' AND market = 'CN' AND option_values = '["6支"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C20"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713060236820' AND market = 'CN' AND option_values = '["20片"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C60"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713060238124' AND market = 'CN' AND option_values = '["60片"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C60"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713060240462' AND market = 'CN' AND option_values = '["60片"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C100"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713070242147' AND market = 'CN' AND option_values = '["100粒"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C20"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713070244792' AND market = 'CN' AND option_values = '["20袋"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C100"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713070246602' AND market = 'CN' AND option_values = '["100片"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C10"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713070248514' AND market = 'CN' AND option_values = '["10片"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C50"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713070250193' AND market = 'CN' AND option_values = '["50支"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C100"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713070252211' AND market = 'CN' AND option_values = '["100片"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C50"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713080254693' AND market = 'CN' AND option_values = '["50只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C20"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713080256661' AND market = 'CN' AND option_values = '["20只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713080258706' AND market = 'CN' AND option_values = '["单支"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713080260651' AND market = 'CN' AND option_values = '["单支"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713080262674' AND market = 'CN' AND option_values = '["单台"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_CSET"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713090264317' AND market = 'CN' AND option_values = '["套装"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_SIZE_SZONE"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713090266917' AND market = 'CN' AND option_values = '["均码"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C10"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713090268945' AND market = 'CN' AND option_values = '["10片"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713090270056' AND market = 'CN' AND option_values = '["单瓶"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_DURATION_D120"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713100276120' AND market = 'CN' AND option_values = '["2小时"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_DURATION_D240"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713100278856' AND market = 'CN' AND option_values = '["4小时"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713100284810' AND market = 'CN' AND option_values = '["单台"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713110286854' AND market = 'CN' AND option_values = '["单台"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713110288365' AND market = 'CN' AND option_values = '["单台"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713110290217' AND market = 'CN' AND option_values = '["单台"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713110292760' AND market = 'CN' AND option_values = '["单台"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_DURATION_D240"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713120304518' AND market = 'CN' AND option_values = '["4小时"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713130310064' AND market = 'CN' AND option_values = '["单只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713130312371' AND market = 'CN' AND option_values = '["单只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713130314566' AND market = 'CN' AND option_values = '["单只"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713130316795' AND market = 'CN' AND option_values = '["单台"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713130318895' AND market = 'CN' AND option_values = '["单台"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713140320193' AND market = 'CN' AND option_values = '["单台"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713140330601' AND market = 'CN' AND option_values = '["单件"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
INSERT INTO prd_spec_dim (dim_no, code, name, value_type, unit, usage_type, universal, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('SD_AREA', 'AREA', '面积', 'QUANT', '㎡', 'SALE', 0, 90, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
INSERT INTO prd_spec_value (value_no, dim_no, code, label, numeric_value, numeric_unit, aliases, scope, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('SV_AREA_A1', 'SD_AREA', 'A1', '1㎡', 1, '㎡', '["每㎡", "每平米", "每平方米"]', 'PLATFORM', 1, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_AREA_A5', 'SD_AREA', 'A5', '5㎡', 5, '㎡', '["5㎡内", "5平米"]', 'PLATFORM', 5, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_AREA_A10', 'SD_AREA', 'A10', '10㎡', 10, '㎡', '["10㎡内", "10平米"]', 'PLATFORM', 10, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_AREA_A80', 'SD_AREA', 'A80', '80㎡', 80, '㎡', '["80㎡内", "80平米"]', 'PLATFORM', 80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('SV_AREA_A100', 'SD_AREA', 'A100', '100㎡', 100, '㎡', '["100㎡内", "100平米"]', 'PLATFORM', 100, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
INSERT INTO prd_spec_value (value_no, dim_no, code, label, numeric_value, numeric_unit, aliases, scope, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('SV_LENGTH_L18M', 'SD_LENGTH', 'L18M', '1.8m', 1.8, 'm', '["1.8米", "1米8"]', 'PLATFORM', 18, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
UPDATE prd_spec_value SET aliases = '["每米", "每延米", "1米"]', updated_at = NOW(), updated_by = 'SYSTEM'
WHERE value_no = 'SV_LENGTH_L1M';
UPDATE prd_spec_value SET aliases = '["单个", "单包", "1件", "1只", "1只装", "1片", "1片装", "1盒", "1盒装", "1袋", "1袋装", "1份", "1份装", "1支", "1支装", "1枚", "1枚装", "1个", "1个装", "1包", "1包装", "1卷", "1卷装", "1条", "1条装", "1粒", "1粒装", "1颗", "1颗装", "1根", "1根装", "1双", "1双装", "1块", "1块装", "1瓶", "1瓶装", "1罐", "1罐装", "1杯", "1杯装", "1听", "1听装", "单台", "单张", "单只", "单支", "单盒", "单袋", "单件", "单份", "单块", "单瓶", "单罐", "单杯", "单条", "单根", "单双", "单片", "单卷", "单粒", "单颗", "1碗", "1碗装", "1盅", "1盅装", "单碗", "单盅", "单把", "每把", "每窗", "单窗", "每台", "每只", "每件"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C1';
UPDATE prd_spec_value SET aliases = '["2只", "2只装", "2片", "2片装", "2盒", "2盒装", "2袋", "2袋装", "2份", "2份装", "2支", "2支装", "2枚", "2枚装", "2个", "2个装", "2包", "2包装", "2卷", "2卷装", "2条", "2条装", "2粒", "2粒装", "2颗", "2颗装", "2根", "2根装", "2双", "2双装", "2块", "2块装", "2瓶", "2瓶装", "2罐", "2罐装", "2杯", "2杯装", "2听", "2听装", "2件", "2碗", "2碗装", "2盅", "2盅装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C2';
UPDATE prd_spec_value SET aliases = '["3只", "3只装", "3片", "3片装", "3盒", "3盒装", "3袋", "3袋装", "3份", "3份装", "3支", "3支装", "3枚", "3枚装", "3个", "3个装", "3包", "3包装", "3卷", "3卷装", "3条", "3条装", "3粒", "3粒装", "3颗", "3颗装", "3根", "3根装", "3双", "3双装", "3块", "3块装", "3瓶", "3瓶装", "3罐", "3罐装", "3杯", "3杯装", "3听", "3听装", "3件", "3碗", "3碗装", "3盅", "3盅装", "三人位", "3人位"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C3';
UPDATE prd_spec_value SET aliases = '["4只装", "4只", "4片", "4片装", "4盒", "4盒装", "4袋", "4袋装", "4份", "4份装", "4支", "4支装", "4枚", "4枚装", "4个", "4个装", "4包", "4包装", "4卷", "4卷装", "4条", "4条装", "4粒", "4粒装", "4颗", "4颗装", "4根", "4根装", "4双", "4双装", "4块", "4块装", "4瓶", "4瓶装", "4罐", "4罐装", "4杯", "4杯装", "4听", "4听装", "4件", "4碗", "4碗装", "4盅", "4盅装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C4';
UPDATE prd_spec_value SET aliases = '["5只", "5只装", "5片", "5片装", "5盒", "5盒装", "5袋", "5袋装", "5份", "5份装", "5支", "5支装", "5枚", "5枚装", "5个", "5个装", "5包", "5包装", "5卷", "5卷装", "5条", "5条装", "5粒", "5粒装", "5颗", "5颗装", "5根", "5根装", "5双", "5双装", "5块", "5块装", "5瓶", "5瓶装", "5罐", "5罐装", "5杯", "5杯装", "5听", "5听装", "5件", "5碗", "5碗装", "5盅", "5盅装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C5';
UPDATE prd_spec_value SET aliases = '["6只装", "6卷", "6只", "6片", "6片装", "6盒", "6盒装", "6袋", "6袋装", "6份", "6份装", "6支", "6支装", "6枚", "6枚装", "6个", "6个装", "6包", "6包装", "6卷装", "6条", "6条装", "6粒", "6粒装", "6颗", "6颗装", "6根", "6根装", "6双", "6双装", "6块", "6块装", "6瓶", "6瓶装", "6罐", "6罐装", "6杯", "6杯装", "6听", "6听装", "6件", "6碗", "6碗装", "6盅", "6盅装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C6';
UPDATE prd_spec_value SET aliases = '["8只", "8只装", "8片", "8片装", "8盒", "8盒装", "8袋", "8袋装", "8份", "8份装", "8支", "8支装", "8枚", "8枚装", "8个", "8个装", "8包", "8包装", "8卷", "8卷装", "8条", "8条装", "8粒", "8粒装", "8颗", "8颗装", "8根", "8根装", "8双", "8双装", "8块", "8块装", "8瓶", "8瓶装", "8罐", "8罐装", "8杯", "8杯装", "8听", "8听装", "8件", "8碗", "8碗装", "8盅", "8盅装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C8';
UPDATE prd_spec_value SET aliases = '["9只", "9只装", "9片", "9片装", "9盒", "9盒装", "9袋", "9袋装", "9份", "9份装", "9支", "9支装", "9枚", "9枚装", "9个", "9个装", "9包", "9包装", "9卷", "9卷装", "9条", "9条装", "9粒", "9粒装", "9颗", "9颗装", "9根", "9根装", "9双", "9双装", "9块", "9块装", "9瓶", "9瓶装", "9罐", "9罐装", "9杯", "9杯装", "9听", "9听装", "9件", "9碗", "9碗装", "9盅", "9盅装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C9';
UPDATE prd_spec_value SET aliases = '["10只", "10只装", "10片", "10片装", "10盒", "10盒装", "10袋", "10袋装", "10份", "10份装", "10支", "10支装", "10枚", "10枚装", "10个", "10个装", "10包", "10包装", "10卷", "10卷装", "10条", "10条装", "10粒", "10粒装", "10颗", "10颗装", "10根", "10根装", "10双", "10双装", "10块", "10块装", "10瓶", "10瓶装", "10罐", "10罐装", "10杯", "10杯装", "10听", "10听装", "10件", "10碗", "10碗装", "10盅", "10盅装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C10';
UPDATE prd_spec_value SET aliases = '["12只", "12只装", "12片", "12片装", "12盒", "12盒装", "12袋", "12袋装", "12份", "12份装", "12支", "12支装", "12枚", "12枚装", "12个", "12个装", "12包", "12包装", "12卷", "12卷装", "12条", "12条装", "12粒", "12粒装", "12颗", "12颗装", "12根", "12根装", "12双", "12双装", "12块", "12块装", "12瓶", "12瓶装", "12罐", "12罐装", "12杯", "12杯装", "12听", "12听装", "12件", "12碗", "12碗装", "12盅", "12盅装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C12';
UPDATE prd_spec_value SET aliases = '["20只", "20只装", "20片", "20片装", "20盒", "20盒装", "20袋", "20袋装", "20份", "20份装", "20支", "20支装", "20枚", "20枚装", "20个", "20个装", "20包", "20包装", "20卷", "20卷装", "20条", "20条装", "20粒", "20粒装", "20颗", "20颗装", "20根", "20根装", "20双", "20双装", "20块", "20块装", "20瓶", "20瓶装", "20罐", "20罐装", "20杯", "20杯装", "20听", "20听装", "20件", "20碗", "20碗装", "20盅", "20盅装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C20';
UPDATE prd_spec_value SET aliases = '["24只", "24只装", "24片", "24片装", "24盒", "24盒装", "24袋", "24袋装", "24份", "24份装", "24支", "24支装", "24枚", "24枚装", "24个", "24个装", "24包", "24包装", "24卷", "24卷装", "24条", "24条装", "24粒", "24粒装", "24颗", "24颗装", "24根", "24根装", "24双", "24双装", "24块", "24块装", "24瓶", "24瓶装", "24罐", "24罐装", "24杯", "24杯装", "24听", "24听装", "24件", "24碗", "24碗装", "24盅", "24盅装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C24';
UPDATE prd_spec_value SET aliases = '["27只", "27只装", "27片", "27片装", "27盒", "27盒装", "27袋", "27袋装", "27份", "27份装", "27支", "27支装", "27枚", "27枚装", "27个", "27个装", "27包", "27包装", "27卷", "27卷装", "27条", "27条装", "27粒", "27粒装", "27颗", "27颗装", "27根", "27根装", "27双", "27双装", "27块", "27块装", "27瓶", "27瓶装", "27罐", "27罐装", "27杯", "27杯装", "27听", "27听装", "27件", "27碗", "27碗装", "27盅", "27盅装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C27';
UPDATE prd_spec_value SET aliases = '["32只", "32只装", "32片", "32片装", "32盒", "32盒装", "32袋", "32袋装", "32份", "32份装", "32支", "32支装", "32枚", "32枚装", "32个", "32个装", "32包", "32包装", "32卷", "32卷装", "32条", "32条装", "32粒", "32粒装", "32颗", "32颗装", "32根", "32根装", "32双", "32双装", "32块", "32块装", "32瓶", "32瓶装", "32罐", "32罐装", "32杯", "32杯装", "32听", "32听装", "32件", "32碗", "32碗装", "32盅", "32盅装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C32';
UPDATE prd_spec_value SET aliases = '["50只", "50只装", "50片", "50片装", "50盒", "50盒装", "50袋", "50袋装", "50份", "50份装", "50支", "50支装", "50枚", "50枚装", "50个", "50个装", "50包", "50包装", "50卷", "50卷装", "50条", "50条装", "50粒", "50粒装", "50颗", "50颗装", "50根", "50根装", "50双", "50双装", "50块", "50块装", "50瓶", "50瓶装", "50罐", "50罐装", "50杯", "50杯装", "50听", "50听装", "50件", "50碗", "50碗装", "50盅", "50盅装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C50';
UPDATE prd_spec_value SET aliases = '["60只", "60只装", "60片", "60片装", "60盒", "60盒装", "60袋", "60袋装", "60份", "60份装", "60支", "60支装", "60枚", "60枚装", "60个", "60个装", "60包", "60包装", "60卷", "60卷装", "60条", "60条装", "60粒", "60粒装", "60颗", "60颗装", "60根", "60根装", "60双", "60双装", "60块", "60块装", "60瓶", "60瓶装", "60罐", "60罐装", "60杯", "60杯装", "60听", "60听装", "60件", "60碗", "60碗装", "60盅", "60盅装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C60';
UPDATE prd_spec_value SET aliases = '["100只", "100只装", "100片", "100片装", "100盒", "100盒装", "100袋", "100袋装", "100份", "100份装", "100支", "100支装", "100枚", "100枚装", "100个", "100个装", "100包", "100包装", "100卷", "100卷装", "100条", "100条装", "100粒", "100粒装", "100颗", "100颗装", "100根", "100根装", "100双", "100双装", "100块", "100块装", "100瓶", "100瓶装", "100罐", "100罐装", "100杯", "100杯装", "100听", "100听装", "100件", "100碗", "100碗装", "100盅", "100盅装"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE value_no = 'SV_COUNT_C100';
INSERT INTO prd_spec_value (value_no, dim_no, code, label, numeric_value, numeric_unit, aliases, scope, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('SV_DIAMETER_DM32', 'SD_DIAMETER', 'DM32', '32cm', 32, 'cm', NULL, 'PLATFORM', 32, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
INSERT INTO prd_category_spec (category_no, dim_no, usage_type, is_primary, required, sort, status, tenant_no, created_at, created_by, updated_at, updated_by) VALUES
  ('CAT310', 'SD_AREA',     NULL, 0, 0,  80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT310', 'SD_LENGTH',   NULL, 0, 0,  90, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT310', 'SD_TIMES',    NULL, 0, 0, 100, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_AREA',     NULL, 0, 0,  80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_LENGTH',   NULL, 0, 0,  90, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT320', 'SD_TIMES',    NULL, 0, 0, 100, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT140', 'SD_VOLUME',   NULL, 0, 0,  90, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT240', 'SD_VOLUME',   NULL, 0, 0,  80, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM'),
  ('CAT220', 'SD_DIAMETER', NULL, 0, 0,  50, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM');
UPDATE prd_goods SET spec_groups = '[{"name": "口径", "options": ["32cm"], "optionCodes": ["DM32"], "templateNo": "SD_DIAMETER"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201712520091725' AND spec_groups = '[{"name":"规格","options":["32cm"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["4碗"], "optionCodes": ["C4"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713020197988' AND spec_groups = '[{"name":"规格","options":["4碗"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["2碗"], "optionCodes": ["C2"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713020199300' AND spec_groups = '[{"name":"规格","options":["2碗"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "容量", "options": ["1L"], "optionCodes": ["V1L"], "templateNo": "SD_VOLUME"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713020201366' AND spec_groups = '[{"name":"规格","options":["1L"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["1盅"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713030213165' AND spec_groups = '[{"name":"规格","options":["1盅"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "容量", "options": ["30ml"], "optionCodes": ["V30ML"], "templateNo": "SD_VOLUME"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713090271483' AND spec_groups = '[{"name":"规格","options":["30ml"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "容量", "options": ["60ml"], "optionCodes": ["V60ML"], "templateNo": "SD_VOLUME"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713100273988' AND spec_groups = '[{"name":"规格","options":["60ml"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "面积", "options": ["100㎡内"], "optionCodes": ["A100"], "templateNo": "SD_AREA"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713100279753' AND spec_groups = '[{"name":"规格","options":["100㎡内"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "面积", "options": ["10㎡"], "optionCodes": ["A10"], "templateNo": "SD_AREA"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713100281445' AND spec_groups = '[{"name":"规格","options":["10㎡"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["三人位"], "optionCodes": ["C3"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713110293804' AND spec_groups = '[{"name":"规格","options":["三人位"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "面积", "options": ["每㎡"], "optionCodes": ["A1"], "templateNo": "SD_AREA"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713120295783' AND spec_groups = '[{"name":"规格","options":["每㎡"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "长度", "options": ["1.8m"], "optionCodes": ["L18M"], "templateNo": "SD_LENGTH"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713120297614' AND spec_groups = '[{"name":"规格","options":["1.8m"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "面积", "options": ["100㎡"], "optionCodes": ["A100"], "templateNo": "SD_AREA"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713120299081' AND spec_groups = '[{"name":"规格","options":["100㎡"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "面积", "options": ["80㎡内"], "optionCodes": ["A80"], "templateNo": "SD_AREA"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713120301756' AND spec_groups = '[{"name":"规格","options":["80㎡内"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "次数", "options": ["单次"], "optionCodes": ["T1"], "templateNo": "SD_TIMES"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713130305610' AND spec_groups = '[{"name":"规格","options":["单次"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "次数", "options": ["单次"], "optionCodes": ["T1"], "templateNo": "SD_TIMES"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713130307041' AND spec_groups = '[{"name":"规格","options":["单次"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["单把"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713140321597' AND spec_groups = '[{"name":"规格","options":["单把"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "次数", "options": ["单次"], "optionCodes": ["T1"], "templateNo": "SD_TIMES"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713140323884' AND spec_groups = '[{"name":"规格","options":["单次"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "面积", "options": ["5㎡"], "optionCodes": ["A5"], "templateNo": "SD_AREA"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713140325596' AND spec_groups = '[{"name":"规格","options":["5㎡"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "数量", "options": ["每窗"], "optionCodes": ["C1"], "templateNo": "SD_COUNT"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713140327690' AND spec_groups = '[{"name":"规格","options":["每窗"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "次数", "options": ["单次"], "optionCodes": ["T1"], "templateNo": "SD_TIMES"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713150331250' AND spec_groups = '[{"name":"规格","options":["单次"]}]';
UPDATE prd_goods SET spec_groups = '[{"name": "长度", "options": ["每米"], "optionCodes": ["L1M"], "templateNo": "SD_LENGTH"}]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE goods_no = 'G202608201713150333788' AND spec_groups = '[{"name":"规格","options":["每米"]}]';
UPDATE prd_sku SET option_value_nos = '["SV_DIAMETER_DM32"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201712520092747' AND market = 'CN' AND option_values = '["32cm"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C4"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713020198891' AND market = 'CN' AND option_values = '["4碗"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C2"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713020200365' AND market = 'CN' AND option_values = '["2碗"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_VOLUME_V1L"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713020202288' AND market = 'CN' AND option_values = '["1L"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713030214652' AND market = 'CN' AND option_values = '["1盅"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_VOLUME_V30ML"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713090272056' AND market = 'CN' AND option_values = '["30ml"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_VOLUME_V60ML"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713100274080' AND market = 'CN' AND option_values = '["60ml"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_AREA_A100"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713100280900' AND market = 'CN' AND option_values = '["100㎡内"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_AREA_A10"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713100282196' AND market = 'CN' AND option_values = '["10㎡"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C3"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713110294601' AND market = 'CN' AND option_values = '["三人位"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_AREA_A1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713120296336' AND market = 'CN' AND option_values = '["每㎡"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_LENGTH_L18M"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713120298806' AND market = 'CN' AND option_values = '["1.8m"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_AREA_A100"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713120300405' AND market = 'CN' AND option_values = '["100㎡"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_AREA_A80"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713120302730' AND market = 'CN' AND option_values = '["80㎡内"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_TIMES_T1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713130306319' AND market = 'CN' AND option_values = '["单次"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_TIMES_T1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713130308772' AND market = 'CN' AND option_values = '["单次"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713140322871' AND market = 'CN' AND option_values = '["单把"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_TIMES_T1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713140324772' AND market = 'CN' AND option_values = '["单次"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_AREA_A5"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713140326864' AND market = 'CN' AND option_values = '["5㎡"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_COUNT_C1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713140328623' AND market = 'CN' AND option_values = '["每窗"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_TIMES_T1"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713150332314' AND market = 'CN' AND option_values = '["单次"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
UPDATE prd_sku SET option_value_nos = '["SV_LENGTH_L1M"]', updated_at = NOW(), updated_by = 'SYSTEM' WHERE sku_no = 'SK202608201713150334016' AND market = 'CN' AND option_values = '["每米"]' AND (option_value_nos IS NULL OR option_value_nos IN ('', '[]'));
