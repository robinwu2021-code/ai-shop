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
    PRIMARY KEY (id),
    CONSTRAINT uk_spu_std_no UNIQUE (std_no)
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
