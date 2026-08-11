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
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
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
    goods_nos JSON DEFAULT NULL,
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

CREATE TABLE IF NOT EXISTS msg_message
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    message_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
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
    PRIMARY KEY (id),
    CONSTRAINT uk_message_no UNIQUE (message_no),
    CONSTRAINT uk_msg_dedup UNIQUE (dedup_key)
);

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
    CONSTRAINT uk_sub_user_template UNIQUE (user_no,template_id)
);

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
    points_config INT(11) DEFAULT NULL,
    group_price_minor BIGINT(20) DEFAULT NULL,
    group_min_count INT(11) DEFAULT NULL,
    sellable_override JSON DEFAULT NULL,
    title_i18n TEXT DEFAULT NULL,
    subtitle_i18n TEXT DEFAULT NULL,
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
    options JSON NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
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
    images JSON DEFAULT NULL,
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
    images JSON DEFAULT NULL,
    spec VARCHAR(255) DEFAULT NULL,
    like_count INT(11) NOT NULL DEFAULT 0,
    reply VARCHAR(512) DEFAULT NULL,
    replied_at BIGINT(20) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PASSED',
    reject_reason VARCHAR(255) DEFAULT NULL,
    risk_flags JSON DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
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
    pay_methods JSON DEFAULT NULL,
    markets JSON DEFAULT NULL,
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
    pay_methods JSON DEFAULT NULL,
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
    role VARCHAR(16) NOT NULL,
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
    PRIMARY KEY (id),
    CONSTRAINT uk_store_goods UNIQUE (store_no,goods_no)
);

CREATE TABLE IF NOT EXISTS msg_template
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
    PRIMARY KEY (id),
    CONSTRAINT uk_msg_template_no UNIQUE (template_no)
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
    PRIMARY KEY (id),
    CONSTRAINT uk_community_apply_no UNIQUE (apply_no)
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
