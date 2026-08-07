-- 【自动生成，勿手改】由 backend/scripts/gen-test-schema.py 重放 db/migration/V*.sql 得到。
-- 生产是 MySQL 方言；这份是 H2 等价物（去列注释与普通索引，UNIQUE 转 CONSTRAINT）。
-- 与源文件的漂移由 SchemaDriftTest 拦截。


CREATE TABLE IF NOT EXISTS sys_outbox
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_no       VARCHAR(64)  NOT NULL,
    aggregate_type VARCHAR(32)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    retry_count    INT          NOT NULL DEFAULT 0,
    next_retry_at  DATETIME     NULL,
    last_error     VARCHAR(512) NULL,
    created_at     DATETIME     NOT NULL,
    sent_at        DATETIME     NULL,
    CONSTRAINT uk_event_no UNIQUE (event_no)
);

CREATE TABLE IF NOT EXISTS sys_idempotent
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    idem_key    VARCHAR(128) NOT NULL,
    endpoint    VARCHAR(128) NOT NULL,
    user_no     VARCHAR(64)  NULL,
    result_json TEXT         NULL,
    created_at  DATETIME     NOT NULL,
    expire_at   DATETIME     NOT NULL,
    CONSTRAINT uk_key_endpoint UNIQUE (idem_key, endpoint)
);

CREATE TABLE IF NOT EXISTS usr_user
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_no      VARCHAR(64) NOT NULL,
    nickname     VARCHAR(64)  NULL,
    avatar       VARCHAR(512) NULL,
    phone        VARCHAR(32)  NULL,
    openid       VARCHAR(64)  NULL,
    unionid      VARCHAR(64)  NULL,
    apple_sub    VARCHAR(128) NULL,
    community_no VARCHAR(64)  NULL,
    pickup_no    VARCHAR(64)  NULL,
    merchant_no  VARCHAR(64)  NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    created_by   VARCHAR(64)  NULL,
    updated_at   DATETIME    NOT NULL,
    updated_by   VARCHAR(64)  NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_user_no UNIQUE (user_no),
    CONSTRAINT uk_openid UNIQUE (openid),
    CONSTRAINT uk_phone UNIQUE (phone),
    CONSTRAINT uk_apple_sub UNIQUE (apple_sub)
);

CREATE TABLE IF NOT EXISTS usr_merchant
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_no   VARCHAR(64)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    logo          VARCHAR(512)  NULL,
    type          VARCHAR(16)  NOT NULL DEFAULT 'PERSONAL',
    tier          VARCHAR(16)   NULL,
    description   VARCHAR(512)  NULL,
    address       VARCHAR(255)  NULL,
    open_hours    VARCHAR(64)   NULL,
    owner_user_no VARCHAR(64)   NULL,
    rating        INT          NOT NULL DEFAULT 50,
    rating_count  INT          NOT NULL DEFAULT 0,
    sales_count   INT          NOT NULL DEFAULT 0,
    goods_count   INT          NOT NULL DEFAULT 0,
    score_goods   INT          NOT NULL DEFAULT 50,
    score_service INT          NOT NULL DEFAULT 50,
    score_speed   INT          NOT NULL DEFAULT 50,
    verified      TINYINT      NOT NULL DEFAULT 0,
    breach_count  INT          NOT NULL DEFAULT 0,
    tags          VARCHAR(512)  NULL,
    joined_at     BIGINT        NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'APPLYING',
    tenant_no     VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME     NOT NULL,
    created_by    VARCHAR(64)   NULL,
    updated_at    DATETIME     NOT NULL,
    updated_by    VARCHAR(64)   NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    store_code VARCHAR(32) NULL,
    service_scope VARCHAR(16) NOT NULL DEFAULT 'COMMUNITY',
    service_city_code VARCHAR(32) NULL,
    points_enabled TINYINT NOT NULL DEFAULT 0,
    points_forced TINYINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_merchant_no UNIQUE (merchant_no),
    CONSTRAINT uk_store_code UNIQUE (store_code)
);

CREATE TABLE IF NOT EXISTS cmt_community
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    community_no VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    address      VARCHAR(255)  NULL,
    lat_e6       INT           NULL,
    lng_e6       INT           NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    tenant_no    VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME     NOT NULL,
    created_by   VARCHAR(64)   NULL,
    updated_at   DATETIME     NOT NULL,
    updated_by   VARCHAR(64)   NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    city_code VARCHAR(32) NULL,
    points_enabled TINYINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_community_no UNIQUE (community_no)
);

CREATE TABLE IF NOT EXISTS cmt_pickup_point
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    pickup_no        VARCHAR(64)  NOT NULL,
    community_no     VARCHAR(64)  NOT NULL,
    name             VARCHAR(128) NOT NULL,
    address          VARCHAR(255)  NULL,
    lat_e6           INT           NULL,
    lng_e6           INT           NULL,
    type             VARCHAR(16)  NOT NULL DEFAULT 'STORE',
    scope            VARCHAR(16)  NOT NULL DEFAULT 'PERMANENT',
    owner_ref        VARCHAR(64)   NULL,
    group_no         VARCHAR(64)   NULL,
    open_hours       VARCHAR(64)   NULL,
    arrival_desc     VARCHAR(128)  NULL,
    service_fee_rate INT          NOT NULL DEFAULT 0,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    tenant_no        VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at       DATETIME     NOT NULL,
    created_by       VARCHAR(64)   NULL,
    updated_at       DATETIME     NOT NULL,
    updated_by       VARCHAR(64)   NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    time_slot VARCHAR(64) NULL,
    service_fee_per_item_minor BIGINT NOT NULL DEFAULT 0,
    fee_mode VARCHAR(16) NOT NULL DEFAULT 'NONE',
    CONSTRAINT uk_pickup_no UNIQUE (pickup_no),
    CONSTRAINT ck_neighbor_zero_fee CHECK (type <> 'NEIGHBOR' OR service_fee_rate = 0)
);

CREATE TABLE IF NOT EXISTS prd_goods
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    goods_no       VARCHAR(64)  NOT NULL,
    merchant_no    VARCHAR(64)  NOT NULL,
    title          VARCHAR(255) NOT NULL,
    subtitle       VARCHAR(255)  NULL,
    cover          VARCHAR(512)  NULL,
    images         TEXT          NULL,
    type           VARCHAR(16)  NOT NULL,
    category_no    VARCHAR(64)   NULL,
    fulfillments   VARCHAR(255)  NULL,
    spec_groups    TEXT          NULL,
    rating         INT          NOT NULL DEFAULT 50,
    rating_count   INT          NOT NULL DEFAULT 0,
    sales          INT          NOT NULL DEFAULT 0,
    limit_per_user INT          NOT NULL DEFAULT 0,
    on_sale        TINYINT      NOT NULL DEFAULT 0,
    audit_status   VARCHAR(16)  NOT NULL DEFAULT 'AUDITING',
    cutoff_at      BIGINT        NULL,
    arrival_desc   VARCHAR(128)  NULL,
    weighed        TINYINT       NULL,
    origin         VARCHAR(64)   NULL,
    duration_min   INT           NULL,
    store_name     VARCHAR(128)  NULL,
    tenant_no      VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at     DATETIME     NOT NULL,
    created_by     VARCHAR(64)   NULL,
    updated_at     DATETIME     NOT NULL,
    updated_by     VARCHAR(64)   NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    points_config INT NULL,
    group_price_minor BIGINT NULL,
    group_min_count INT NULL,
    sellable_override JSON NULL,
    CONSTRAINT uk_goods_no UNIQUE (goods_no)
);

CREATE TABLE IF NOT EXISTS prd_sku
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku_no        VARCHAR(64) NOT NULL,
    goods_no      VARCHAR(64) NOT NULL,
    merchant_no   VARCHAR(64) NOT NULL,
    market        VARCHAR(8)  NOT NULL DEFAULT 'CN',
    option_values VARCHAR(512) NULL,
    spec          VARCHAR(128) NULL,
    price         BIGINT      NOT NULL,
    origin_price  BIGINT       NULL,
    stock         INT         NOT NULL DEFAULT 0,
    locked_stock  INT         NOT NULL DEFAULT 0,
    nominal_gram  INT          NULL,
    tenant_no     VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME    NOT NULL,
    created_by    VARCHAR(64)  NULL,
    updated_at    DATETIME    NOT NULL,
    updated_by    VARCHAR(64)  NULL,
    version       BIGINT      NOT NULL DEFAULT 0,
    deleted       TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_merchant_sku_market UNIQUE (merchant_no, sku_no, market)
);

CREATE TABLE IF NOT EXISTS prd_community_pool
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    community_no VARCHAR(64) NOT NULL,
    goods_no     VARCHAR(64) NOT NULL,
    merchant_no  VARCHAR(64) NOT NULL,
    sort_weight  INT         NOT NULL DEFAULT 0,
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    created_by   VARCHAR(64)  NULL,
    updated_at   DATETIME    NOT NULL,
    updated_by   VARCHAR(64)  NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_community_goods UNIQUE (community_no, goods_no)
);

CREATE TABLE IF NOT EXISTS trd_cart_item
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_no    VARCHAR(64) NOT NULL,
    goods_no   VARCHAR(64) NOT NULL,
    sku_no     VARCHAR(64) NOT NULL,
    qty        INT         NOT NULL DEFAULT 1,
    selected   TINYINT     NOT NULL DEFAULT 1,
    tenant_no  VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME    NOT NULL,
    created_by VARCHAR(64)  NULL,
    updated_at DATETIME    NOT NULL,
    updated_by VARCHAR(64)  NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    deleted    TINYINT     NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS ord_order
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no        VARCHAR(64) NOT NULL,
    user_no         VARCHAR(64) NOT NULL,
    community_no    VARCHAR(64)  NULL,
    pay_amount      BIGINT      NOT NULL DEFAULT 0,
    goods_amount    BIGINT      NOT NULL DEFAULT 0,
    freight_amount  BIGINT      NOT NULL DEFAULT 0,
    discount_amount BIGINT      NOT NULL DEFAULT 0,
    currency        VARCHAR(8)  NOT NULL DEFAULT 'CNY',
    status          VARCHAR(16) NOT NULL DEFAULT 'WAIT_PAY',
    pay_channel     VARCHAR(16)  NULL,
    pay_trade_no    VARCHAR(64)  NULL,
    paid_at         BIGINT       NULL,
    pay_deadline_at       BIGINT       NULL,
    cancel_reason   VARCHAR(255) NULL,
    tenant_no       VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at      DATETIME    NOT NULL,
    created_by      VARCHAR(64)  NULL,
    updated_at      DATETIME    NOT NULL,
    updated_by      VARCHAR(64)  NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    deleted         TINYINT     NOT NULL DEFAULT 0,
    pay_scene VARCHAR(16) NULL,
    CONSTRAINT uk_order_no UNIQUE (order_no)
);

CREATE TABLE IF NOT EXISTS ord_sub_order
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    sub_order_no    VARCHAR(64)  NOT NULL,
    order_no        VARCHAR(64)  NOT NULL,
    user_no         VARCHAR(64)  NOT NULL,
    merchant_no     VARCHAR(64)  NOT NULL,
    merchant_name   VARCHAR(128)  NULL,
    fulfillment     VARCHAR(24)   NULL,
    pickup_no       VARCHAR(64)   NULL,
    address_id      VARCHAR(64)   NULL,
    traffic_source  VARCHAR(24)   NULL,
    goods_amount    BIGINT       NOT NULL DEFAULT 0,
    freight_amount  BIGINT       NOT NULL DEFAULT 0,
    discount_amount BIGINT       NOT NULL DEFAULT 0,
    pay_amount      BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'WAIT_PAY',
    verify_code     VARCHAR(16)   NULL,
    remark          VARCHAR(255)  NULL,
    tenant_no       VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at      DATETIME     NOT NULL,
    created_by      VARCHAR(64)   NULL,
    updated_at      DATETIME     NOT NULL,
    updated_by      VARCHAR(64)   NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    pickup_name VARCHAR(128) NULL,
    discount_platform BIGINT NOT NULL DEFAULT 0,
    discount_merchant BIGINT NOT NULL DEFAULT 0,
    weigh_adjust_minor BIGINT NOT NULL DEFAULT 0,
    appointment_at BIGINT NULL,
    group_no VARCHAR(64) NULL,
    express_no VARCHAR(64) NULL,
    buyer_nickname VARCHAR(64) NULL,
    reviewed TINYINT NOT NULL DEFAULT 0,
    points_deduct INT NOT NULL DEFAULT 0,
    points_deduct_minor BIGINT NOT NULL DEFAULT 0,
    points_granted TINYINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_sub_order_no UNIQUE (sub_order_no),
    CONSTRAINT uk_verify_code UNIQUE (verify_code)
);

CREATE TABLE IF NOT EXISTS ord_item
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    sub_order_no  VARCHAR(64)  NOT NULL,
    order_no      VARCHAR(64)  NOT NULL,
    goods_no      VARCHAR(64)  NOT NULL,
    sku_no        VARCHAR(64)  NOT NULL,
    title         VARCHAR(255)  NULL,
    cover         VARCHAR(512)  NULL,
    spec          VARCHAR(128)  NULL,
    price         BIGINT       NOT NULL DEFAULT 0,
    qty           INT          NOT NULL DEFAULT 1,
    amount        BIGINT       NOT NULL DEFAULT 0,
    category_type VARCHAR(16)   NULL,
    tenant_no     VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME     NOT NULL,
    created_by    VARCHAR(64)   NULL,
    updated_at    DATETIME     NOT NULL,
    updated_by    VARCHAR(64)   NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    nominal_gram INT NULL,
    weighed TINYINT NOT NULL DEFAULT 0,
    is_gift TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS prd_stock_lock
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_no    VARCHAR(64) NOT NULL,
    sku_no     VARCHAR(64) NOT NULL,
    qty        INT         NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'LOCKED',
    locked_at  DATETIME    NOT NULL,
    settled_at DATETIME     NULL,
    tenant_no  VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME    NOT NULL,
    created_by VARCHAR(64)  NULL,
    updated_at DATETIME    NOT NULL,
    updated_by VARCHAR(64)  NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    deleted    TINYINT     NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS usr_address
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    address_id VARCHAR(64)  NOT NULL,
    user_no    VARCHAR(64)  NOT NULL,
    name       VARCHAR(64)  NOT NULL,
    phone      VARCHAR(32)  NOT NULL,
    province   VARCHAR(64)   NULL,
    city       VARCHAR(64)   NULL,
    district   VARCHAR(64)   NULL,
    detail     VARCHAR(255) NOT NULL,
    lat_e6     INT           NULL,
    lng_e6     INT           NULL,
    is_default TINYINT      NOT NULL DEFAULT 0,
    tag        VARCHAR(16)   NULL,
    tenant_no  VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at DATETIME     NOT NULL,
    created_by VARCHAR(64)   NULL,
    updated_at DATETIME     NOT NULL,
    updated_by VARCHAR(64)   NULL,
    version    BIGINT       NOT NULL DEFAULT 0,
    deleted    TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_address_id UNIQUE (address_id)
);

CREATE TABLE IF NOT EXISTS prd_category
(
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_no            VARCHAR(64)  NOT NULL,
    parent_no              VARCHAR(64)   NULL,
    level                  INT          NOT NULL DEFAULT 1,
    name                   VARCHAR(64)  NOT NULL,
    icon                   VARCHAR(512)  NULL,
    sort                   INT          NOT NULL DEFAULT 0,
    attr_template          TEXT          NULL,
    qualification_required VARCHAR(512)  NULL,
    status                 VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    tenant_no              VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at             DATETIME     NOT NULL,
    created_by             VARCHAR(64)   NULL,
    updated_at             DATETIME     NOT NULL,
    updated_by             VARCHAR(64)   NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
    deleted                TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_category_no UNIQUE (category_no)
);

CREATE TABLE IF NOT EXISTS ord_status_log
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    sub_order_no  VARCHAR(64) NOT NULL,
    status        VARCHAR(16) NOT NULL,
    label         VARCHAR(64)  NULL,
    operator_type VARCHAR(16) NOT NULL DEFAULT 'SYSTEM',
    operator_no   VARCHAR(64)  NULL,
    at            BIGINT      NOT NULL,
    tenant_no     VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME    NOT NULL
);

CREATE TABLE IF NOT EXISTS ful_verify_log
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    sub_order_no  VARCHAR(64)  NULL,
    pickup_no     VARCHAR(64) NOT NULL,
    verify_code   VARCHAR(16) NOT NULL,
    verify_type   VARCHAR(16) NOT NULL DEFAULT 'SCAN',
    operator_no   VARCHAR(64) NOT NULL,
    result        VARCHAR(24) NOT NULL,
    at            BIGINT      NOT NULL,
    tenant_no     VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME    NOT NULL
);

CREATE TABLE IF NOT EXISTS ful_batch
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no     VARCHAR(64) NOT NULL,
    pickup_no    VARCHAR(64) NOT NULL,
    arrive_date  VARCHAR(16) NOT NULL,
    total_qty    INT         NOT NULL DEFAULT 0,
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    received_at  BIGINT       NULL,
    received_by  VARCHAR(64)  NULL,
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    created_by   VARCHAR(64)  NULL,
    updated_at   DATETIME    NOT NULL,
    updated_by   VARCHAR(64)  NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_batch_no UNIQUE (batch_no)
);

CREATE TABLE IF NOT EXISTS ord_after_sale
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    after_sale_no   VARCHAR(64)  NOT NULL,
    sub_order_no    VARCHAR(64)  NOT NULL,
    order_no        VARCHAR(64)  NOT NULL,
    user_no         VARCHAR(64)  NOT NULL,
    merchant_no     VARCHAR(64)  NOT NULL,
    type            VARCHAR(16)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'APPLIED',
    reason          VARCHAR(255) NOT NULL,
    images          TEXT          NULL,
    refund_minor    BIGINT       NOT NULL DEFAULT 0,
    instant         TINYINT      NOT NULL DEFAULT 0,
    merchant_remark VARCHAR(255)  NULL,
    express_company VARCHAR(64)   NULL,
    express_no      VARCHAR(64)   NULL,
    liability       VARCHAR(16)   NULL,
    split_reversed  TINYINT      NOT NULL DEFAULT 0,
    refunded_at     BIGINT        NULL,
    tenant_no       VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at      DATETIME     NOT NULL,
    created_by      VARCHAR(64)   NULL,
    updated_at      DATETIME     NOT NULL,
    updated_by      VARCHAR(64)   NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    dispute_reason VARCHAR(512) NULL,
    CONSTRAINT uk_after_sale_no UNIQUE (after_sale_no)
);

CREATE TABLE IF NOT EXISTS mkt_attribution
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_no     VARCHAR(64) NOT NULL,
    merchant_no VARCHAR(64)  NULL,
    inviter_no  VARCHAR(64)  NULL,
    channel     VARCHAR(64)  NULL,
    source      VARCHAR(16) NOT NULL,
    expire_at   BIGINT      NOT NULL,
    tenant_no   VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at  DATETIME    NOT NULL,
    created_by  VARCHAR(64)  NULL,
    updated_at  DATETIME    NOT NULL,
    updated_by  VARCHAR(64)  NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_user UNIQUE (user_no)
);

CREATE TABLE IF NOT EXISTS mkt_attribution_log
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_no      VARCHAR(64) NOT NULL,
    merchant_no  VARCHAR(64)  NULL,
    inviter_no   VARCHAR(64)  NULL,
    channel      VARCHAR(64)  NULL,
    source       VARCHAR(16) NOT NULL,
    decision     VARCHAR(16) NOT NULL,
    prev_source  VARCHAR(16)  NULL,
    prev_ref     VARCHAR(64)  NULL,
    reason       VARCHAR(128) NULL,
    at           BIGINT      NOT NULL,
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL
);

CREATE TABLE IF NOT EXISTS usr_store_favorite
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_no     VARCHAR(64) NOT NULL,
    merchant_no VARCHAR(64) NOT NULL,
    tenant_no   VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at  DATETIME    NOT NULL,
    created_by  VARCHAR(64)  NULL,
    updated_at  DATETIME    NOT NULL,
    updated_by  VARCHAR(64)  NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_user_merchant UNIQUE (user_no, merchant_no)
);

CREATE TABLE IF NOT EXISTS mkt_coupon
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    coupon_no           VARCHAR(64)  NOT NULL,
    title               VARCHAR(128) NOT NULL,
    type                VARCHAR(16)  NOT NULL,
    face_minor          BIGINT       NOT NULL DEFAULT 0,
    discount_rate       INT          NOT NULL DEFAULT 0,
    threshold_minor     BIGINT       NOT NULL DEFAULT 0,
    max_discount_minor  BIGINT       NOT NULL DEFAULT 0,
    funder              VARCHAR(16)  NOT NULL DEFAULT 'PLATFORM',
    merchant_no         VARCHAR(64)   NULL,
    total_count         INT          NOT NULL DEFAULT 0,
    received_count      INT          NOT NULL DEFAULT 0,
    per_user_limit      INT          NOT NULL DEFAULT 1,
    start_at            BIGINT       NOT NULL,
    end_at              BIGINT       NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    tenant_no           VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at          DATETIME     NOT NULL,
    created_by          VARCHAR(64)   NULL,
    updated_at          DATETIME     NOT NULL,
    updated_by          VARCHAR(64)   NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    deleted             TINYINT      NOT NULL DEFAULT 0,
    scope_desc VARCHAR(255) NULL,
    CONSTRAINT uk_coupon_no UNIQUE (coupon_no)
);

CREATE TABLE IF NOT EXISTS mkt_user_coupon
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_coupon_no  VARCHAR(64) NOT NULL,
    coupon_no       VARCHAR(64) NOT NULL,
    user_no         VARCHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'UNUSED',
    order_no        VARCHAR(64)  NULL,
    received_at     BIGINT      NOT NULL,
    used_at         BIGINT       NULL,
    tenant_no       VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at      DATETIME    NOT NULL,
    created_by      VARCHAR(64)  NULL,
    updated_at      DATETIME    NOT NULL,
    updated_by      VARCHAR(64)  NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    deleted         TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_user_coupon_no UNIQUE (user_coupon_no)
);

CREATE TABLE IF NOT EXISTS mkt_group_buy
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_no           VARCHAR(64)  NOT NULL,
    goods_no           VARCHAR(64)  NOT NULL,
    sku_no             VARCHAR(64)   NULL,
    merchant_no        VARCHAR(64)  NOT NULL,
    title              VARCHAR(255)  NULL,
    cover              VARCHAR(512)  NULL,
    group_price_minor  BIGINT       NOT NULL,
    origin_price_minor BIGINT       NOT NULL DEFAULT 0,
    min_count          INT          NOT NULL DEFAULT 2,
    joined_count       INT          NOT NULL DEFAULT 0,
    status             VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    end_at             BIGINT       NOT NULL,
    tenant_no          VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at         DATETIME     NOT NULL,
    created_by         VARCHAR(64)   NULL,
    updated_at         DATETIME     NOT NULL,
    updated_by         VARCHAR(64)   NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    deleted            TINYINT      NOT NULL DEFAULT 0,
    pickup_no VARCHAR(64) NULL,
    initiator_user_no VARCHAR(64) NULL,
    CONSTRAINT uk_group_no UNIQUE (group_no)
);

CREATE TABLE IF NOT EXISTS mkt_group_member
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_no   VARCHAR(64) NOT NULL,
    user_no    VARCHAR(64) NOT NULL,
    nickname   VARCHAR(64)  NULL,
    joined_at  BIGINT      NOT NULL,
    tenant_no  VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME    NOT NULL,
    created_by VARCHAR(64)  NULL,
    updated_at DATETIME    NOT NULL,
    updated_by VARCHAR(64)  NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    deleted    TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_group_user UNIQUE (group_no, user_no)
);

CREATE TABLE IF NOT EXISTS mkt_request
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_no      VARCHAR(64)  NOT NULL,
    owner_id        VARCHAR(64)  NOT NULL,
    title           VARCHAR(255) NOT NULL,
    description     TEXT          NULL,
    images          TEXT          NULL,
    expect_count    INT          NOT NULL DEFAULT 1,
    interest_count  INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'COLLECTING',
    chosen_quote_no VARCHAR(64)   NULL,
    locked_price    BIGINT        NULL,
    end_at          BIGINT       NOT NULL,
    tenant_no       VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at      DATETIME     NOT NULL,
    created_by      VARCHAR(64)   NULL,
    updated_at      DATETIME     NOT NULL,
    updated_by      VARCHAR(64)   NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    pickup_no VARCHAR(64) NULL,
    budget_minor BIGINT NULL,
    group_no VARCHAR(64) NULL,
    CONSTRAINT uk_request_no UNIQUE (request_no)
);

CREATE TABLE IF NOT EXISTS mkt_request_interest
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_no VARCHAR(64) NOT NULL,
    user_no    VARCHAR(64) NOT NULL,
    at         BIGINT      NOT NULL,
    tenant_no  VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME    NOT NULL,
    created_by VARCHAR(64)  NULL,
    updated_at DATETIME    NOT NULL,
    updated_by VARCHAR(64)  NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    deleted    TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_request_user UNIQUE (request_no, user_no)
);

CREATE TABLE IF NOT EXISTS mkt_quote
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    quote_no         VARCHAR(64)  NOT NULL,
    request_no       VARCHAR(64)  NOT NULL,
    merchant_no      VARCHAR(64)  NOT NULL,
    unit_price_minor BIGINT       NOT NULL,
    min_qty          INT          NOT NULL DEFAULT 1,
    note             VARCHAR(512)  NULL,
    valid_until      BIGINT       NOT NULL,
    revision_count   INT          NOT NULL DEFAULT 0,
    chosen           TINYINT      NOT NULL DEFAULT 0,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    tenant_no        VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at       DATETIME     NOT NULL,
    created_by       VARCHAR(64)   NULL,
    updated_at       DATETIME     NOT NULL,
    updated_by       VARCHAR(64)   NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_quote_no UNIQUE (quote_no),
    CONSTRAINT uk_request_merchant UNIQUE (request_no, merchant_no)
);

CREATE TABLE IF NOT EXISTS mkt_quote_revision
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    quote_no         VARCHAR(64) NOT NULL,
    request_no       VARCHAR(64) NOT NULL,
    merchant_no      VARCHAR(64) NOT NULL,
    from_price_minor BIGINT      NOT NULL,
    to_price_minor   BIGINT      NOT NULL,
    raised           TINYINT     NOT NULL DEFAULT 0,
    at               BIGINT      NOT NULL,
    tenant_no        VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at       DATETIME    NOT NULL
);

CREATE TABLE IF NOT EXISTS stl_bill
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    settle_no        VARCHAR(64) NOT NULL,
    sub_order_no     VARCHAR(64) NOT NULL,
    order_no         VARCHAR(64) NOT NULL,
    merchant_no      VARCHAR(64) NOT NULL,
    gross_minor      BIGINT      NOT NULL DEFAULT 0,
    commission_minor BIGINT      NOT NULL DEFAULT 0,
    service_fee_minor BIGINT     NOT NULL DEFAULT 0,
    net_minor        BIGINT      NOT NULL DEFAULT 0,
    traffic_source   VARCHAR(24)  NULL,
    commission_rate  INT         NOT NULL DEFAULT 0,
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    split_at         BIGINT       NULL,
    retry_count      INT         NOT NULL DEFAULT 0,
    last_error       VARCHAR(512) NULL,
    tenant_no        VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at       DATETIME    NOT NULL,
    created_by       VARCHAR(64)  NULL,
    updated_at       DATETIME    NOT NULL,
    updated_by       VARCHAR(64)  NULL,
    version          BIGINT      NOT NULL DEFAULT 0,
    deleted          TINYINT     NOT NULL DEFAULT 0,
    channel VARCHAR(16) NOT NULL DEFAULT 'WECHAT',
    pay_scene VARCHAR(16) NULL,
    channel_fee_minor BIGINT NOT NULL DEFAULT 0,
    channel_fee_rate INT NOT NULL DEFAULT 0,
    channel_fee_source VARCHAR(16) NULL,
    fee_bearer VARCHAR(16) NOT NULL DEFAULT 'MERCHANT',
    CONSTRAINT uk_settle_no UNIQUE (settle_no),
    CONSTRAINT uk_sub_order UNIQUE (sub_order_no)
);

CREATE TABLE IF NOT EXISTS stl_split_log
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    settle_no    VARCHAR(64) NOT NULL,
    sub_order_no VARCHAR(64) NOT NULL,
    split_action VARCHAR(16) NOT NULL,
    amount_minor BIGINT      NOT NULL,
    request_no   VARCHAR(64) NOT NULL,
    result       VARCHAR(16) NOT NULL,
    provider_no  VARCHAR(64)  NULL,
    message      VARCHAR(512) NULL,
    at           BIGINT      NOT NULL,
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    CONSTRAINT uk_split_request_no UNIQUE (request_no)
);

CREATE TABLE IF NOT EXISTS msg_message
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_no  VARCHAR(64)  NOT NULL,
    user_no     VARCHAR(64)  NOT NULL,
    msg_type    VARCHAR(16)  NOT NULL,
    title       VARCHAR(128) NOT NULL,
    body        VARCHAR(512)  NULL,
    link        VARCHAR(255)  NULL,
    is_read     TINYINT      NOT NULL DEFAULT 0,
    dedup_key   VARCHAR(128)  NULL,
    at          BIGINT       NOT NULL,
    tenant_no   VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at  DATETIME     NOT NULL,
    created_by  VARCHAR(64)   NULL,
    updated_at  DATETIME     NOT NULL,
    updated_by  VARCHAR(64)   NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_message_no UNIQUE (message_no),
    CONSTRAINT uk_msg_dedup UNIQUE (dedup_key)
);

CREATE TABLE IF NOT EXISTS msg_ticket
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_no   VARCHAR(64)  NOT NULL,
    user_no     VARCHAR(64)  NOT NULL,
    subject     VARCHAR(128) NOT NULL,
    content     VARCHAR(1024) NOT NULL,
    order_no    VARCHAR(64)   NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    reply       VARCHAR(1024) NULL,
    replied_at  BIGINT        NULL,
    replied_by  VARCHAR(64)   NULL,
    tenant_no   VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at  DATETIME     NOT NULL,
    created_by  VARCHAR(64)   NULL,
    updated_at  DATETIME     NOT NULL,
    updated_by  VARCHAR(64)   NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_ticket_no UNIQUE (ticket_no)
);

CREATE TABLE IF NOT EXISTS msg_subscribe
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_no     VARCHAR(64) NOT NULL,
    template_id VARCHAR(64) NOT NULL,
    accepted    TINYINT     NOT NULL DEFAULT 0,
    at          BIGINT      NOT NULL,
    tenant_no   VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at  DATETIME    NOT NULL,
    created_by  VARCHAR(64)  NULL,
    updated_at  DATETIME    NOT NULL,
    updated_by  VARCHAR(64)  NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_sub_user_template UNIQUE (user_no, template_id)
);

CREATE TABLE IF NOT EXISTS sys_staff
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    staff_no   VARCHAR(64)  NOT NULL,
    username   VARCHAR(64)  NOT NULL,
    password   VARCHAR(128) NOT NULL,
    real_name  VARCHAR(64)   NULL,
    roles      VARCHAR(255)  NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    tenant_no  VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at DATETIME     NOT NULL,
    created_by VARCHAR(64)   NULL,
    updated_at DATETIME     NOT NULL,
    updated_by VARCHAR(64)   NULL,
    version    BIGINT       NOT NULL DEFAULT 0,
    deleted    TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_staff_no UNIQUE (staff_no),
    CONSTRAINT uk_staff_username UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS sys_audit_log
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    staff_no   VARCHAR(64)  NOT NULL,
    staff_name VARCHAR(64)   NULL,
    op_action  VARCHAR(64)  NOT NULL,
    target     VARCHAR(128)  NULL,
    detail     VARCHAR(512)  NULL,
    at         BIGINT       NOT NULL,
    tenant_no  VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at DATETIME     NOT NULL
);

CREATE TABLE IF NOT EXISTS usr_merchant_apply
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    apply_no       VARCHAR(64)  NOT NULL,
    user_no        VARCHAR(64)  NOT NULL,
    merchant_no    VARCHAR(64)   NULL,
    name           VARCHAR(128) NOT NULL,
    merchant_type  VARCHAR(16)  NOT NULL DEFAULT 'PERSONAL',
    contact_phone  VARCHAR(32)   NULL,
    qualifications TEXT          NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    reject_reason  VARCHAR(255)  NULL,
    audited_by     VARCHAR(64)   NULL,
    audited_at     BIGINT        NULL,
    tenant_no      VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at     DATETIME     NOT NULL,
    created_by     VARCHAR(64)   NULL,
    updated_at     DATETIME     NOT NULL,
    updated_by     VARCHAR(64)   NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_apply_no UNIQUE (apply_no)
);

CREATE TABLE IF NOT EXISTS usr_merchant_community
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_no  VARCHAR(64) NOT NULL,
    community_no VARCHAR(64) NOT NULL,
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    created_by   VARCHAR(64)  NULL,
    updated_at   DATETIME    NOT NULL,
    updated_by   VARCHAR(64)  NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_merchant_community UNIQUE (merchant_no, community_no)
);

CREATE TABLE IF NOT EXISTS rvw_review
(
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    review_no         VARCHAR(64)  NOT NULL,
    sub_order_no      VARCHAR(64)  NOT NULL,
    order_no          VARCHAR(64)  NOT NULL,
    goods_no          VARCHAR(64)  NOT NULL,
    sku_no            VARCHAR(64)   NULL,
    merchant_no       VARCHAR(64)  NOT NULL,
    user_no           VARCHAR(64)  NOT NULL,
    nickname          VARCHAR(64)   NULL,
    avatar            VARCHAR(512)  NULL,
    rating            TINYINT      NOT NULL,
    score_goods       TINYINT       NULL,
    score_fulfillment TINYINT       NULL,
    score_service     TINYINT       NULL,
    content           VARCHAR(1024) NULL,
    images            JSON          NULL,
    spec              VARCHAR(255)  NULL,
    like_count        INT          NOT NULL DEFAULT 0,
    reply             VARCHAR(512)  NULL,
    replied_at        BIGINT        NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PASSED',
    reject_reason     VARCHAR(255)  NULL,
    risk_flags        JSON          NULL,
    tenant_no         VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at        DATETIME     NOT NULL,
    created_by        VARCHAR(64)   NULL,
    updated_at        DATETIME     NOT NULL,
    updated_by        VARCHAR(64)   NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    deleted           TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_review_no UNIQUE (review_no),
    CONSTRAINT uk_order_goods UNIQUE (sub_order_no, goods_no)
);

CREATE TABLE IF NOT EXISTS rvw_appeal
(
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    appeal_no    VARCHAR(64)  NOT NULL,
    review_no    VARCHAR(64)  NOT NULL,
    merchant_no  VARCHAR(64)  NOT NULL,
    reason       VARCHAR(512) NOT NULL,
    images       JSON          NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    submitted_at BIGINT       NOT NULL,
    verdict      VARCHAR(512)  NULL,
    decided_at   BIGINT        NULL,
    decided_by   VARCHAR(64)   NULL,
    tenant_no    VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME     NOT NULL,
    created_by   VARCHAR(64)   NULL,
    updated_at   DATETIME     NOT NULL,
    updated_by   VARCHAR(64)   NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_appeal_no UNIQUE (appeal_no),
    CONSTRAINT uk_review UNIQUE (review_no)
);

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
    CONSTRAINT uk_review_user UNIQUE (review_no, user_no)
);

CREATE TABLE IF NOT EXISTS mkt_campaign
(
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    campaign_no       VARCHAR(64)  NOT NULL,
    merchant_no       VARCHAR(64)  NOT NULL,
    type              VARCHAR(16)  NOT NULL,
    name              VARCHAR(128) NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    start_at          BIGINT       NOT NULL,
    end_at            BIGINT       NOT NULL,
    threshold_minor   BIGINT        NULL,
    discount_minor    BIGINT        NULL,
    flash_price_minor BIGINT        NULL,
    buy_n             INT           NULL,
    gift_m            INT           NULL,
    goods_nos         JSON          NULL,
    total_count       INT           NULL,
    taken_count       INT          NOT NULL DEFAULT 0,
    used_count        INT          NOT NULL DEFAULT 0,
    tenant_no         VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at        DATETIME     NOT NULL,
    created_by        VARCHAR(64)   NULL,
    updated_at        DATETIME     NOT NULL,
    updated_by        VARCHAR(64)   NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    deleted           TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_campaign_no UNIQUE (campaign_no)
);

CREATE TABLE IF NOT EXISTS prd_spec_template
(
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    template_no   VARCHAR(64)  NOT NULL,
    scope         VARCHAR(16)  NOT NULL DEFAULT 'MERCHANT',
    category_type VARCHAR(16)   NULL,
    name          VARCHAR(64)  NOT NULL,
    options       JSON         NOT NULL,
    merchant_no   VARCHAR(64)   NULL,
    tenant_no     VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME     NOT NULL,
    created_by    VARCHAR(64)   NULL,
    updated_at    DATETIME     NOT NULL,
    updated_by    VARCHAR(64)   NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_template_no UNIQUE (template_no)
);

CREATE TABLE IF NOT EXISTS pts_user_account
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_no    VARCHAR(64) NOT NULL,
    balance    BIGINT      NOT NULL DEFAULT 0,
    total_earn BIGINT      NOT NULL DEFAULT 0,
    total_use  BIGINT      NOT NULL DEFAULT 0,
    tenant_no  VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME    NOT NULL,
    created_by VARCHAR(64)  NULL,
    updated_at DATETIME    NOT NULL,
    updated_by VARCHAR(64)  NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    deleted    TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_pts_acc_user UNIQUE (user_no)
);

CREATE TABLE IF NOT EXISTS pts_user_ledger
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    ledger_no      VARCHAR(64) NOT NULL,
    user_no        VARCHAR(64) NOT NULL,
    biz_type       VARCHAR(16) NOT NULL,
    points         BIGINT      NOT NULL,
    balance_after  BIGINT      NOT NULL,
    remaining      BIGINT       NULL,
    expire_at      BIGINT       NULL,
    issuer_merchant_no VARCHAR(64) NULL,
    sub_order_no   VARCHAR(64)  NULL,
    remark         VARCHAR(255) NULL,
    tenant_no      VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at     DATETIME    NOT NULL,
    created_by     VARCHAR(64)  NULL,
    updated_at     DATETIME    NOT NULL,
    updated_by     VARCHAR(64)  NULL,
    version        BIGINT      NOT NULL DEFAULT 0,
    deleted        TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_pts_ledger_no UNIQUE (ledger_no)
);

CREATE TABLE IF NOT EXISTS pts_redeem_alloc
(
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    alloc_no             VARCHAR(64) NOT NULL,
    use_ledger_no        VARCHAR(64) NOT NULL,
    earn_ledger_no       VARCHAR(64) NOT NULL,
    user_no              VARCHAR(64) NOT NULL,
    sub_order_no         VARCHAR(64) NOT NULL,
    issuer_merchant_no   VARCHAR(64) NOT NULL,
    acceptor_merchant_no VARCHAR(64) NOT NULL,
    points               BIGINT      NOT NULL,
    amount_minor         BIGINT      NOT NULL,
    rate_snapshot        INT         NOT NULL,
    self_used            TINYINT     NOT NULL DEFAULT 0,
    status               VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    period               VARCHAR(8)   NULL,
    confirmed_at         BIGINT       NULL,
    tenant_no            VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at           DATETIME    NOT NULL,
    created_by           VARCHAR(64)  NULL,
    updated_at           DATETIME    NOT NULL,
    updated_by           VARCHAR(64)  NULL,
    version              BIGINT      NOT NULL DEFAULT 0,
    deleted              TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_pts_alloc_no UNIQUE (alloc_no)
);

CREATE TABLE IF NOT EXISTS pts_merchant_quota
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_no  VARCHAR(64) NOT NULL,
    credit_limit BIGINT      NOT NULL DEFAULT 0,
    used         BIGINT      NOT NULL DEFAULT 0,
    suspended    TINYINT     NOT NULL DEFAULT 0,
    suspended_at BIGINT       NULL,
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    created_by   VARCHAR(64)  NULL,
    updated_at   DATETIME    NOT NULL,
    updated_by   VARCHAR(64)  NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_pts_quota_merchant UNIQUE (merchant_no)
);

CREATE TABLE IF NOT EXISTS pts_merchant_ledger
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    ledger_no          VARCHAR(64) NOT NULL,
    merchant_no        VARCHAR(64) NOT NULL,
    biz_type           VARCHAR(16) NOT NULL,
    quota_delta        BIGINT      NOT NULL DEFAULT 0,
    amount_delta_minor BIGINT      NOT NULL DEFAULT 0,
    quota_used_after   BIGINT       NULL,
    counterparty_no    VARCHAR(64)  NULL,
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
    CONSTRAINT uk_pts_m_ledger_no UNIQUE (ledger_no)
);

CREATE TABLE IF NOT EXISTS stl_points_bill
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    bill_no       VARCHAR(64) NOT NULL,
    merchant_no   VARCHAR(64) NOT NULL,
    period        VARCHAR(8)  NOT NULL,
    income_minor  BIGINT      NOT NULL DEFAULT 0,
    expense_minor BIGINT      NOT NULL DEFAULT 0,
    net_minor     BIGINT      NOT NULL DEFAULT 0,
    alloc_count   INT         NOT NULL DEFAULT 0,
    status        VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    settled_at    BIGINT       NULL,
    tenant_no     VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME    NOT NULL,
    created_by    VARCHAR(64)  NULL,
    updated_at    DATETIME    NOT NULL,
    updated_by    VARCHAR(64)  NULL,
    version       BIGINT      NOT NULL DEFAULT 0,
    deleted       TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_pts_bill_no UNIQUE (bill_no),
    CONSTRAINT uk_pts_bill_merchant_period UNIQUE (merchant_no, period)
);

CREATE TABLE IF NOT EXISTS stl_points_pool
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    flow_no      VARCHAR(64) NOT NULL,
    direction    VARCHAR(8)  NOT NULL,
    pool_type    VARCHAR(24) NOT NULL,
    amount_minor BIGINT      NOT NULL,
    balance_after BIGINT     NOT NULL,
    merchant_no  VARCHAR(64)  NULL,
    period       VARCHAR(8)   NULL,
    ref_no       VARCHAR(64)  NULL,
    remark       VARCHAR(255) NULL,
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    created_by   VARCHAR(64)  NULL,
    updated_at   DATETIME    NOT NULL,
    updated_by   VARCHAR(64)  NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_pts_pool_flow_no UNIQUE (flow_no)
);

CREATE TABLE IF NOT EXISTS ful_group_pickup
(
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    pickup_no    VARCHAR(64)  NOT NULL,
    group_no     VARCHAR(64)  NOT NULL,
    user_no      VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    address      VARCHAR(255) NOT NULL,
    time_slot    VARCHAR(64)   NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    received_at  BIGINT        NULL,
    tenant_no    VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME     NOT NULL,
    created_by   VARCHAR(64)   NULL,
    updated_at   DATETIME     NOT NULL,
    updated_by   VARCHAR(64)   NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_group_pickup_no UNIQUE (pickup_no),
    CONSTRAINT uk_group UNIQUE (group_no)
);

CREATE TABLE IF NOT EXISTS usr_merchant_payment
(
    id                    BIGINT       AUTO_INCREMENT PRIMARY KEY,
    merchant_no           VARCHAR(64)  NOT NULL,
    channel               VARCHAR(16)  NOT NULL,
    subject_type          VARCHAR(16)  NOT NULL DEFAULT 'MICRO',
    sub_mchid             VARCHAR(64)   NULL,
    apply_no              VARCHAR(64)   NULL,
    apply_status          VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    reject_reason         VARCHAR(512)  NULL,
    pay_methods           JSON          NULL,
    invoice_capable       TINYINT      NOT NULL DEFAULT 0,
    settle_account_type   VARCHAR(24)   NULL,
    settle_account_masked VARCHAR(64)   NULL,
    fee_bearer            VARCHAR(16)  NOT NULL DEFAULT 'MERCHANT',
    applied_at            BIGINT        NULL,
    activated_at          BIGINT        NULL,
    tenant_no             VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at            DATETIME     NOT NULL,
    created_by            VARCHAR(64)   NULL,
    updated_at            DATETIME     NOT NULL,
    updated_by            VARCHAR(64)   NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    deleted               TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_mp_merchant_channel UNIQUE (merchant_no, channel)
);

CREATE TABLE IF NOT EXISTS sys_channel_category_rule
(
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    scene         VARCHAR(16)  NOT NULL,
    category_type VARCHAR(16)  NOT NULL,
    sellable      TINYINT      NOT NULL DEFAULT 1,
    reason        VARCHAR(255)  NULL,
    updated_by    VARCHAR(64)   NULL,
    tenant_no     VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME     NOT NULL,
    created_by    VARCHAR(64)   NULL,
    updated_at    DATETIME     NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_ccr_scene_category UNIQUE (scene, category_type)
);
