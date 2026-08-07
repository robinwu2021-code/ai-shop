-- S1 领域表：用户 · 社区 · 自提点 · 商家 · 商品 · SKU · 社区池
-- 约定：业务键 xxx_no 一律建 UNIQUE；所有表带 BaseEntity 的六列（tenant_no/审计/version/deleted）。

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
    merchant_no  VARCHAR(64)  NULL COMMENT '常去店（进店归因 C-ST-09）',
    status       VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    created_by   VARCHAR(64)  NULL,
    updated_at   DATETIME    NOT NULL,
    updated_by   VARCHAR(64)  NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    UNIQUE KEY uk_user_no (user_no),
    -- 三种登录方式各自的稳定标识都要能唯一命中，否则并发首登会建出两个账号
    UNIQUE KEY uk_openid (openid),
    UNIQUE KEY uk_phone (phone),
    UNIQUE KEY uk_apple_sub (apple_sub)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'C 端用户';

CREATE TABLE IF NOT EXISTS usr_merchant
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_no   VARCHAR(64)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    logo          VARCHAR(512)  NULL,
    type          VARCHAR(16)  NOT NULL DEFAULT 'PERSONAL' COMMENT 'PERSONAL/INDIVIDUAL/COMPANY',
    tier          VARCHAR(16)   NULL COMMENT '分层预留 P-11.1.6',
    description   VARCHAR(512)  NULL,
    address       VARCHAR(255)  NULL,
    open_hours    VARCHAR(64)   NULL,
    owner_user_no VARCHAR(64)   NULL COMMENT '店主的 C 端用户号 —— B 端权限的源头',
    rating        INT          NOT NULL DEFAULT 50 COMMENT '评分 ×10',
    rating_count  INT          NOT NULL DEFAULT 0,
    sales_count   INT          NOT NULL DEFAULT 0,
    goods_count   INT          NOT NULL DEFAULT 0,
    score_goods   INT          NOT NULL DEFAULT 50,
    score_service INT          NOT NULL DEFAULT 50,
    score_speed   INT          NOT NULL DEFAULT 50,
    verified      TINYINT      NOT NULL DEFAULT 0,
    breach_count  INT          NOT NULL DEFAULT 0 COMMENT '毁约次数，>0 在报价卡公示（ADR-003）',
    tags          VARCHAR(512)  NULL COMMENT 'JSON 数组',
    joined_at     BIGINT        NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'APPLYING',
    tenant_no     VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME     NOT NULL,
    created_by    VARCHAR(64)   NULL,
    updated_at    DATETIME     NOT NULL,
    updated_by    VARCHAR(64)   NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_merchant_no (merchant_no),
    KEY idx_owner (owner_user_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商家主体';

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
    UNIQUE KEY uk_community_no (community_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '社区';

CREATE TABLE IF NOT EXISTS cmt_pickup_point
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    pickup_no        VARCHAR(64)  NOT NULL,
    community_no     VARCHAR(64)  NOT NULL,
    name             VARCHAR(128) NOT NULL,
    address          VARCHAR(255)  NULL,
    lat_e6           INT           NULL,
    lng_e6           INT           NULL,
    type             VARCHAR(16)  NOT NULL DEFAULT 'STORE' COMMENT 'STORE/NEIGHBOR',
    scope            VARCHAR(16)  NOT NULL DEFAULT 'PERMANENT' COMMENT 'PERMANENT/GROUP_INSTANCE',
    owner_ref        VARCHAR(64)   NULL COMMENT 'STORE=merchant_no, NEIGHBOR=user_no',
    group_no         VARCHAR(64)   NULL,
    open_hours       VARCHAR(64)   NULL,
    arrival_desc     VARCHAR(128)  NULL,
    service_fee_rate INT          NOT NULL DEFAULT 0 COMMENT '万分比；NEIGHBOR 必须为 0',
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    tenant_no        VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at       DATETIME     NOT NULL,
    created_by       VARCHAR(64)   NULL,
    updated_at       DATETIME     NOT NULL,
    updated_by       VARCHAR(64)   NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_pickup_no (pickup_no),
    KEY idx_community (community_no),
    KEY idx_owner (owner_ref),
    -- ★ 邻里自提零报酬写进约束而不只写进文档（ADR-005）：
    --   一旦临时点能收钱，就是团长招募换了个名字，ADR-004 消掉的合规问题会原样回来
    CONSTRAINT ck_neighbor_zero_fee CHECK (type <> 'NEIGHBOR' OR service_fee_rate = 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '自提点（ADR-005）';

CREATE TABLE IF NOT EXISTS prd_goods
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    goods_no       VARCHAR(64)  NOT NULL,
    merchant_no    VARCHAR(64)  NOT NULL,
    title          VARCHAR(255) NOT NULL,
    subtitle       VARCHAR(255)  NULL,
    cover          VARCHAR(512)  NULL,
    images         TEXT          NULL COMMENT 'JSON 数组',
    type           VARCHAR(16)  NOT NULL COMMENT 'NORMAL/FRESH/SERVICE/VIRTUAL/CARD',
    category_no    VARCHAR(64)   NULL,
    fulfillments   VARCHAR(255)  NULL COMMENT 'JSON 数组',
    spec_groups    TEXT          NULL COMMENT 'JSON',
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
    UNIQUE KEY uk_goods_no (goods_no),
    KEY idx_merchant (merchant_no),
    KEY idx_type (type),
    KEY idx_title (title(32))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商品 SPU（价格不在这张表）';

CREATE TABLE IF NOT EXISTS prd_sku
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku_no        VARCHAR(64) NOT NULL,
    goods_no      VARCHAR(64) NOT NULL,
    merchant_no   VARCHAR(64) NOT NULL,
    market        VARCHAR(8)  NOT NULL DEFAULT 'CN',
    option_values VARCHAR(512) NULL COMMENT 'JSON 数组',
    spec          VARCHAR(128) NULL COMMENT '展示文案，后端下发',
    price         BIGINT      NOT NULL COMMENT '最小货币单位（分）',
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
    -- ★ 价格的唯一权威：(商家, SKU, 市场)。社区池不存价，从结构上杜绝双入口不同价（R17/B11）
    UNIQUE KEY uk_merchant_sku_market (merchant_no, sku_no, market),
    KEY idx_goods (goods_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'SKU 与价格';

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
    UNIQUE KEY uk_community_goods (community_no, goods_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '社区商品池：只决定可见性，不存价';
