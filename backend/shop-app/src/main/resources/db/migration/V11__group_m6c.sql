-- M6c.3 团购与求团（ADR-003：报价不做事前审核，靠**锁价 + 改价公示 + 毁约记录**）。

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
    min_count          INT          NOT NULL DEFAULT 2 COMMENT '起团人数',
    joined_count       INT          NOT NULL DEFAULT 0,
    status             VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/FORMED/FAILED/CLOSED',
    end_at             BIGINT       NOT NULL,
    tenant_no          VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at         DATETIME     NOT NULL,
    created_by         VARCHAR(64)   NULL,
    updated_at         DATETIME     NOT NULL,
    updated_by         VARCHAR(64)   NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    deleted            TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_group_no (group_no),
    KEY idx_status_end (status, end_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商家团';

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
    -- 一人一团只能参一次：不加这个约束，「还差 N 人」会被同一个人刷满
    UNIQUE KEY uk_group_user (group_no, user_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '参团成员';

CREATE TABLE IF NOT EXISTS mkt_request
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_no      VARCHAR(64)  NOT NULL,
    -- 发起人。**不是身份，是团实例上的字段**（ADR-004）：只有他能选定报价
    owner_id        VARCHAR(64)  NOT NULL,
    title           VARCHAR(255) NOT NULL,
    description     TEXT          NULL,
    images          TEXT          NULL COMMENT 'JSON 数组',
    expect_count    INT          NOT NULL DEFAULT 1,
    interest_count  INT          NOT NULL DEFAULT 0 COMMENT '+1 数：**意向，不是订单**',
    status          VARCHAR(16)  NOT NULL DEFAULT 'COLLECTING'
        COMMENT 'COLLECTING/QUOTED/LOCKED/CONFIRMED/CLOSED',
    -- 选定的报价。**选定即锁价**：之后商家改价不影响这一单（ADR-003）
    chosen_quote_no VARCHAR(64)   NULL,
    locked_price    BIGINT        NULL COMMENT '锁定的单价快照',
    end_at          BIGINT       NOT NULL,
    tenant_no       VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at      DATETIME     NOT NULL,
    created_by      VARCHAR(64)   NULL,
    updated_at      DATETIME     NOT NULL,
    updated_by      VARCHAR(64)   NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_request_no (request_no),
    KEY idx_status (status, end_at),
    KEY idx_owner (owner_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '邻里求团需求单';

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
    UNIQUE KEY uk_request_user (request_no, user_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '求团 +1（意向）';

CREATE TABLE IF NOT EXISTS mkt_quote
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    quote_no         VARCHAR(64)  NOT NULL,
    request_no       VARCHAR(64)  NOT NULL,
    merchant_no      VARCHAR(64)  NOT NULL,
    unit_price_minor BIGINT       NOT NULL,
    min_qty          INT          NOT NULL DEFAULT 1 COMMENT '起订量',
    note             VARCHAR(512)  NULL,
    valid_until      BIGINT       NOT NULL,
    revision_count   INT          NOT NULL DEFAULT 0,
    chosen           TINYINT      NOT NULL DEFAULT 0,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/WITHDRAWN',
    tenant_no        VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at       DATETIME     NOT NULL,
    created_by       VARCHAR(64)   NULL,
    updated_at       DATETIME     NOT NULL,
    updated_by       VARCHAR(64)   NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_quote_no (quote_no),
    -- 一个商家对一个需求单只有一条报价：改价是改这条，不是新开一条
    UNIQUE KEY uk_request_merchant (request_no, merchant_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商家报价';

-- 改价留痕。**这是业务表不是日志表**：C 端要读它来公示涨价（ADR-003 §5）。
-- 不做事前审核的代价就是必须让改价可见 —— 藏起来的话，「报低价钓单再涨价」无人能发现。
CREATE TABLE IF NOT EXISTS mkt_quote_revision
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    quote_no         VARCHAR(64) NOT NULL,
    request_no       VARCHAR(64) NOT NULL,
    merchant_no      VARCHAR(64) NOT NULL,
    from_price_minor BIGINT      NOT NULL,
    to_price_minor   BIGINT      NOT NULL,
    raised           TINYINT     NOT NULL DEFAULT 0 COMMENT '是否涨价',
    at               BIGINT      NOT NULL,
    tenant_no        VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at       DATETIME    NOT NULL,
    KEY idx_request_at (request_no, at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '报价改价留痕（C 端公示用）';
