-- S2 交易表：购物车 · 主单 · 子单 · 订单行 · 库存锁定
-- 两级订单结构（ADR-002 / E3）：钱在主单，货在子单。

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
    deleted    TINYINT     NOT NULL DEFAULT 0,
    KEY idx_user (user_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '购物车（不存价，读时实时算）';

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
    expire_at       BIGINT       NULL,
    cancel_reason   VARCHAR(255) NULL,
    tenant_no       VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at      DATETIME    NOT NULL,
    created_by      VARCHAR(64)  NULL,
    updated_at      DATETIME    NOT NULL,
    updated_by      VARCHAR(64)  NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    deleted         TINYINT     NOT NULL DEFAULT 0,
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user_status (user_no, status),
    -- 超时关单任务按 (status, expire_at) 扫描，这是它唯一的查询模式
    KEY idx_status_expire (status, expire_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '主订单：用户视角，一次支付';

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
    traffic_source  VARCHAR(24)   NULL COMMENT 'MERCHANT_OWNED/PLATFORM，下单时固化',
    goods_amount    BIGINT       NOT NULL DEFAULT 0,
    freight_amount  BIGINT       NOT NULL DEFAULT 0,
    discount_amount BIGINT       NOT NULL DEFAULT 0,
    pay_amount      BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'WAIT_PAY',
    pickup_code     VARCHAR(16)   NULL COMMENT '取货码，支付成功后生成',
    remark          VARCHAR(255)  NULL,
    tenant_no       VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at      DATETIME     NOT NULL,
    created_by      VARCHAR(64)   NULL,
    updated_at      DATETIME     NOT NULL,
    updated_by      VARCHAR(64)   NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_sub_order_no (sub_order_no),
    KEY idx_order (order_no),
    KEY idx_merchant_status (merchant_no, status),
    KEY idx_pickup_code (pickup_no, pickup_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '子订单：商家视角，一次分账一条履约链';

CREATE TABLE IF NOT EXISTS ord_item
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    sub_order_no  VARCHAR(64)  NOT NULL,
    order_no      VARCHAR(64)  NOT NULL,
    goods_no      VARCHAR(64)  NOT NULL,
    sku_no        VARCHAR(64)  NOT NULL,
    title         VARCHAR(255)  NULL COMMENT '下单时快照，不随商品改名变动',
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
    KEY idx_sub_order (sub_order_no),
    KEY idx_order (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '订单行（商品快照）';

CREATE TABLE IF NOT EXISTS prd_stock_lock
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    lock_no    VARCHAR(64) NOT NULL COMMENT '= 订单号',
    sku_no     VARCHAR(64) NOT NULL,
    qty        INT         NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'LOCKED' COMMENT 'LOCKED/RELEASED/CONFIRMED',
    locked_at  DATETIME    NOT NULL,
    settled_at DATETIME     NULL,
    tenant_no  VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME    NOT NULL,
    created_by VARCHAR(64)  NULL,
    updated_at DATETIME    NOT NULL,
    updated_by VARCHAR(64)  NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    deleted    TINYINT     NOT NULL DEFAULT 0,
    KEY idx_lock_status (lock_no, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '库存锁定明细：释放与确认据此幂等';
