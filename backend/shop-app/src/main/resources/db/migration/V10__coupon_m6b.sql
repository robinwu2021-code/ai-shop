-- M6b.3 优惠券。
--
-- **出资方（funder）是这张表最重要的一列**：平台券的钱平台出、商家足额收款；
-- 商家券的钱商家自己出、分账时扣减。没有这一列，M7 分账就无法判断该扣谁的钱（Q9）。

CREATE TABLE IF NOT EXISTS mkt_coupon
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    coupon_no           VARCHAR(64)  NOT NULL,
    title               VARCHAR(128) NOT NULL,
    type                VARCHAR(16)  NOT NULL COMMENT 'FULL_CUT 满减 / DISCOUNT 折扣',
    face_minor          BIGINT       NOT NULL DEFAULT 0 COMMENT '满减面额',
    discount_rate       INT          NOT NULL DEFAULT 0 COMMENT '折扣 ×100，88 = 8.8 折',
    threshold_minor     BIGINT       NOT NULL DEFAULT 0 COMMENT '使用门槛（商品额）',
    max_discount_minor  BIGINT       NOT NULL DEFAULT 0 COMMENT '折扣券封顶，0 = 不封顶',
    -- ★ PLATFORM / MERCHANT —— 分账扣款对象（Q9 / db-design §3.4）
    funder              VARCHAR(16)  NOT NULL DEFAULT 'PLATFORM',
    merchant_no         VARCHAR(64)   NULL COMMENT '商家券限本店；平台券为空',
    total_count         INT          NOT NULL DEFAULT 0 COMMENT '发行量',
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
    UNIQUE KEY uk_coupon_no (coupon_no),
    KEY idx_status_time (status, start_at, end_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '优惠券模板';

CREATE TABLE IF NOT EXISTS mkt_user_coupon
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_coupon_no  VARCHAR(64) NOT NULL,
    coupon_no       VARCHAR(64) NOT NULL,
    user_no         VARCHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED/USED/EXPIRED',
    order_no        VARCHAR(64)  NULL COMMENT '用在哪一单（主单）',
    received_at     BIGINT      NOT NULL,
    used_at         BIGINT       NULL,
    tenant_no       VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at      DATETIME    NOT NULL,
    created_by      VARCHAR(64)  NULL,
    updated_at      DATETIME    NOT NULL,
    updated_by      VARCHAR(64)  NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    deleted         TINYINT     NOT NULL DEFAULT 0,
    UNIQUE KEY uk_user_coupon_no (user_coupon_no),
    KEY idx_user_status (user_no, status),
    KEY idx_order (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户券';
