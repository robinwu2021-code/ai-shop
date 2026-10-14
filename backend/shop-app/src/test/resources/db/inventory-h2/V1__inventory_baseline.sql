-- 【自动生成，勿手改】由 backend/scripts/gen-test-schema.py 重放 db/migration/V*.sql 得到。
-- 生产是 MySQL 方言；这份是 H2 等价物（去列注释与普通索引，UNIQUE 转 CONSTRAINT）。
-- 与源文件的漂移由 SchemaDriftTest 拦截。
--
-- ⚠️ **种子一律是 `INSERT IGNORE`，这份产物因此可以被重放。**
--
-- 为什么必须这样：H2 是 `jdbc:h2:mem:shop;DB_CLOSE_DELAY=-1`，库在 Spring context
-- 关掉之后还活着。同一次 mvn 里起第二个 context 时，sql-init 会把这些 INSERT
-- **再跑一遍** —— 普通 INSERT 会撞主键（最常见的是 sys_industry(id)=1），
-- 而症状是**别的测试类**报「Failed to load ApplicationContext」，与那个类本身
-- 毫无关系，且**单独跑永远复现不了**（只有一个 context 时不会重放）。
--
-- 这个坑在 2026-09-29 一天内复发两次，第一次被当成「某个类多声明了 profile」
-- 修掉了症状（改 profile 只是让它排到第一个 context，排序一变就轮到别人）。
-- 幂等才是根治：谁加什么 annotation 都不再影响它。
--
-- H2 2.4.240 + MODE=MySQL 认 `INSERT IGNORE`（实测：重复插入 update count=0，
-- **保留原值不覆盖**，所以测试中途改过的数据不会被后一个 context 的重放冲掉）。
--
-- 仍然值得注意：**多一个 context 就多一次全量重放**，几百条 INSERT 的代价是实打实的。
-- 什么会多起一个 context：@ActiveProfiles 的组合不同、@TestPropertySource、
-- @MockitoBean、自定义 @DynamicPropertySource —— 它们都进 context key。
-- 写测试时只声明真正需要的那些。


CREATE TABLE IF NOT EXISTS inv_owner
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    owner_id     VARCHAR(32)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    external_ref VARCHAR(64)  DEFAULT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(64)  DEFAULT NULL,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by   VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_owner UNIQUE (owner_id),
    CONSTRAINT uk_owner_ext UNIQUE (external_ref)
);

CREATE TABLE IF NOT EXISTS inv_location
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    location_id        VARCHAR(32)  NOT NULL,
    owner_id           VARCHAR(32)  NOT NULL,
    name               VARCHAR(128) NOT NULL,
    kind               VARCHAR(16)  NOT NULL DEFAULT 'STORE',
    external_ref       VARCHAR(64)  DEFAULT NULL,
    source_location_id VARCHAR(32)  DEFAULT NULL,
    is_default         TINYINT      NOT NULL DEFAULT 0,
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         VARCHAR(64)  DEFAULT NULL,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by         VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_loc UNIQUE (owner_id, location_id),
    CONSTRAINT uk_loc_ext UNIQUE (owner_id, external_ref)
);

CREATE TABLE IF NOT EXISTS inv_uom
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    uom_code   VARCHAR(16) NOT NULL,
    name       VARCHAR(32) NOT NULL,
    divisible  TINYINT     NOT NULL DEFAULT 0,
    sort       INT         NOT NULL DEFAULT 0,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(64) DEFAULT NULL,
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_uom UNIQUE (uom_code)
);

CREATE TABLE IF NOT EXISTS inv_item
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    item_id            VARCHAR(32)  NOT NULL,
    owner_id           VARCHAR(32)  NOT NULL,
    item_code          VARCHAR(64)  DEFAULT NULL,
    name               VARCHAR(128) NOT NULL,
    spec_text          VARCHAR(128) DEFAULT NULL,
    spu_id             VARCHAR(32)  DEFAULT NULL,
    category_code      VARCHAR(64)  DEFAULT NULL,
    base_uom           VARCHAR(16)  NOT NULL DEFAULT 'PIECE',
    weighed            TINYINT      NOT NULL DEFAULT 0,
    track_batch        TINYINT      NOT NULL DEFAULT 0,
    shelf_life_days    INT          DEFAULT NULL,
    safety_stock       INT          NOT NULL DEFAULT 0,
    cost_method        VARCHAR(16)  NOT NULL DEFAULT 'LATEST',
    default_cost_minor BIGINT       DEFAULT NULL,
    data_source        VARCHAR(16)  NOT NULL DEFAULT 'SYNCED',
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         VARCHAR(64)  DEFAULT NULL,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by         VARCHAR(64)  DEFAULT NULL,
    source_on_sale TINYINT DEFAULT NULL,
    retired_at DATETIME DEFAULT NULL,
    succeeded_by VARCHAR(40) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_item UNIQUE (owner_id, item_id),
    CONSTRAINT uk_item_code UNIQUE (owner_id, item_code)
);

CREATE TABLE IF NOT EXISTS inv_item_ref
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    owner_id   VARCHAR(32) NOT NULL,
    ref_system VARCHAR(16) NOT NULL,
    ref        VARCHAR(64) NOT NULL,
    item_id    VARCHAR(32) NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ref UNIQUE (owner_id, ref_system, ref)
);

CREATE TABLE IF NOT EXISTS inv_stock_balance
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    owner_id      VARCHAR(32) NOT NULL,
    item_id       VARCHAR(32) NOT NULL,
    location_id   VARCHAR(32) NOT NULL,
    on_hand       INT         NOT NULL DEFAULT 0,
    reserved      INT         NOT NULL DEFAULT 0,
    safety_stock  INT         DEFAULT NULL,
    last_moved_at DATETIME    DEFAULT NULL,
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by      VARCHAR(64) DEFAULT NULL,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_balance UNIQUE (owner_id, item_id, location_id)
);

CREATE TABLE IF NOT EXISTS inv_ledger
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    owner_id        VARCHAR(32) NOT NULL,
    item_id         VARCHAR(32) NOT NULL,
    location_id     VARCHAR(32) NOT NULL,
    doc_kind        VARCHAR(8)  NOT NULL,
    doc_no          VARCHAR(32) NOT NULL,
    line_no         INT         NOT NULL,
    reason_code     VARCHAR(16) NOT NULL,
    qty_delta       INT         NOT NULL,
    balance_after   INT         NOT NULL,
    unit_cost_minor BIGINT      DEFAULT NULL,
    occurred_at     DATETIME    NOT NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    operator        VARCHAR(64) DEFAULT NULL,
    created_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_doc_line UNIQUE (doc_no, line_no)
);

CREATE TABLE IF NOT EXISTS inv_reservation
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    reservation_id VARCHAR(32) NOT NULL,
    owner_id       VARCHAR(32) NOT NULL,
    external_ref   VARCHAR(64) NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'HELD',
    expires_at     DATETIME    NOT NULL,
    committed_at   DATETIME    DEFAULT NULL,
    released_at    DATETIME    DEFAULT NULL,
    outbound_no    VARCHAR(32) DEFAULT NULL,
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     VARCHAR(64) DEFAULT NULL,
    updated_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reservation UNIQUE (owner_id, reservation_id),
    CONSTRAINT uk_reservation_ext UNIQUE (owner_id, external_ref)
);

CREATE TABLE IF NOT EXISTS inv_reservation_line
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    reservation_id VARCHAR(32) NOT NULL,
    line_no        INT         NOT NULL,
    owner_id       VARCHAR(32) NOT NULL,
    item_id        VARCHAR(32) NOT NULL,
    location_id    VARCHAR(32) NOT NULL,
    qty            INT         NOT NULL,
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_res_line UNIQUE (reservation_id, line_no)
);

CREATE TABLE IF NOT EXISTS inv_inbound_order
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    inbound_no       VARCHAR(32)  NOT NULL,
    owner_id         VARCHAR(32)  NOT NULL,
    location_id      VARCHAR(32)  NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    source_ref       VARCHAR(64)  DEFAULT NULL,
    supplier_name    VARCHAR(64)  DEFAULT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    total_qty        INT          NOT NULL DEFAULT 0,
    total_cost_minor BIGINT       NOT NULL DEFAULT 0,
    occurred_at      DATETIME     NOT NULL,
    posted_at        DATETIME     DEFAULT NULL,
    voided_at        DATETIME     DEFAULT NULL,
    operator         VARCHAR(64)  DEFAULT NULL,
    remark           VARCHAR(255) DEFAULT NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(64)  DEFAULT NULL,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by       VARCHAR(64)  DEFAULT NULL,
    supplier_no VARCHAR(32) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_inbound UNIQUE (owner_id, inbound_no)
);

CREATE TABLE IF NOT EXISTS inv_inbound_line
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    inbound_no      VARCHAR(32) NOT NULL,
    line_no         INT         NOT NULL,
    owner_id        VARCHAR(32) NOT NULL,
    item_id         VARCHAR(32) NOT NULL,
    qty             INT         NOT NULL,
    uom             VARCHAR(16) NOT NULL,
    unit_cost_minor BIGINT      DEFAULT NULL,
    batch_no        VARCHAR(32) DEFAULT NULL,
    expire_at       DATE        DEFAULT NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(64) DEFAULT NULL,
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_inbound_line UNIQUE (inbound_no, line_no)
);

CREATE TABLE IF NOT EXISTS inv_outbound_order
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    outbound_no      VARCHAR(32)  NOT NULL,
    owner_id         VARCHAR(32)  NOT NULL,
    location_id      VARCHAR(32)  NOT NULL,
    purpose VARCHAR(16) NOT NULL,
    source_ref       VARCHAR(64)  DEFAULT NULL,
    reservation_id   VARCHAR(32)  DEFAULT NULL,
    reason_code      VARCHAR(16)  DEFAULT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    total_qty        INT          NOT NULL DEFAULT 0,
    total_cost_minor BIGINT       NOT NULL DEFAULT 0,
    occurred_at      DATETIME     NOT NULL,
    posted_at        DATETIME     DEFAULT NULL,
    voided_at        DATETIME     DEFAULT NULL,
    operator         VARCHAR(64)  DEFAULT NULL,
    remark           VARCHAR(255) DEFAULT NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(64)  DEFAULT NULL,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by       VARCHAR(64)  DEFAULT NULL,
    target_type VARCHAR(16) DEFAULT NULL,
    target_no   VARCHAR(32) DEFAULT NULL,
    target_name VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_outbound UNIQUE (owner_id, outbound_no)
);

CREATE TABLE IF NOT EXISTS inv_outbound_line
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    outbound_no     VARCHAR(32) NOT NULL,
    line_no         INT         NOT NULL,
    owner_id        VARCHAR(32) NOT NULL,
    item_id         VARCHAR(32) NOT NULL,
    qty             INT         NOT NULL,
    uom             VARCHAR(16) NOT NULL,
    unit_cost_minor BIGINT      DEFAULT NULL,
    batch_no        VARCHAR(32) DEFAULT NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(64) DEFAULT NULL,
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_outbound_line UNIQUE (outbound_no, line_no)
);

CREATE TABLE IF NOT EXISTS inv_stock_count
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    count_no    VARCHAR(32)  NOT NULL,
    owner_id    VARCHAR(32)  NOT NULL,
    location_id VARCHAR(32)  NOT NULL,
    scope       VARCHAR(16)  NOT NULL DEFAULT 'SELECTED',
    status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    started_at  DATETIME     DEFAULT NULL,
    posted_at   DATETIME     DEFAULT NULL,
    gain_inbound_no  VARCHAR(32) DEFAULT NULL,
    loss_outbound_no VARCHAR(32) DEFAULT NULL,
    operator    VARCHAR(64)  DEFAULT NULL,
    remark      VARCHAR(255) DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(64)  DEFAULT NULL,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_count UNIQUE (owner_id, count_no)
);

CREATE TABLE IF NOT EXISTS inv_stock_count_line
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    count_no    VARCHAR(32) NOT NULL,
    line_no     INT         NOT NULL,
    owner_id    VARCHAR(32) NOT NULL,
    item_id     VARCHAR(32) NOT NULL,
    book_qty    INT         NOT NULL,
    counted_qty INT         DEFAULT NULL,
    diff_qty    INT         DEFAULT NULL,
    reason_code VARCHAR(16) DEFAULT NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by      VARCHAR(64) DEFAULT NULL,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_count_line UNIQUE (count_no, line_no)
);

CREATE TABLE IF NOT EXISTS inv_transfer_order
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    transfer_no        VARCHAR(32)  NOT NULL,
    owner_id           VARCHAR(32)  NOT NULL,
    from_location_id   VARCHAR(32)  NOT NULL,
    to_location_id     VARCHAR(32)  NOT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    shipped_outbound_no VARCHAR(32) DEFAULT NULL,
    received_inbound_no VARCHAR(32) DEFAULT NULL,
    shipped_at         DATETIME     DEFAULT NULL,
    received_at        DATETIME     DEFAULT NULL,
    operator           VARCHAR(64)  DEFAULT NULL,
    remark             VARCHAR(255) DEFAULT NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         VARCHAR(64)  DEFAULT NULL,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64) DEFAULT NULL,
    carrier_no VARCHAR(32) DEFAULT NULL,
    carrier_name VARCHAR(64) DEFAULT NULL,
    tracking_no VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_transfer UNIQUE (owner_id, transfer_no)
);

CREATE TABLE IF NOT EXISTS inv_outbox
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    event_no      VARCHAR(64) NOT NULL,
    owner_id      VARCHAR(32) NOT NULL,
    event_type    VARCHAR(32) NOT NULL,
    payload       TEXT        NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count   INT         NOT NULL DEFAULT 0,
    next_retry_at DATETIME    DEFAULT NULL,
    last_error    VARCHAR(512) DEFAULT NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at       DATETIME    DEFAULT NULL,
    created_by      VARCHAR(64) DEFAULT NULL,
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_event UNIQUE (event_no)
);

CREATE TABLE IF NOT EXISTS inv_daily_snapshot
(
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    owner_id          VARCHAR(32) NOT NULL,
    stat_date         DATE        NOT NULL,
    item_id           VARCHAR(32) NOT NULL,
    location_id       VARCHAR(32) NOT NULL,
    opening_qty       INT         NOT NULL DEFAULT 0,
    inbound_qty       INT         NOT NULL DEFAULT 0,
    outbound_qty      INT         NOT NULL DEFAULT 0,
    sold_qty          INT         NOT NULL DEFAULT 0,
    sold_cost_minor   BIGINT      NOT NULL DEFAULT 0,
    closing_qty       INT         NOT NULL DEFAULT 0,
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(64) DEFAULT NULL,
    updated_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by        VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_snapshot UNIQUE (owner_id, stat_date, item_id, location_id)
);

CREATE TABLE IF NOT EXISTS inv_open_credential
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    credential_id   VARCHAR(32)  NOT NULL,
    owner_id        VARCHAR(32)  NOT NULL,
    app_key         VARCHAR(64)  NOT NULL,
    app_secret_hash VARCHAR(128) NOT NULL,
    name            VARCHAR(64)  DEFAULT NULL,
    scopes          VARCHAR(255) NOT NULL DEFAULT 'read',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    expires_at      DATETIME     DEFAULT NULL,
    last_used_at    DATETIME     DEFAULT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(64)  DEFAULT NULL,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_credential UNIQUE (credential_id),
    CONSTRAINT uk_app_key UNIQUE (app_key)
);

CREATE TABLE IF NOT EXISTS inv_supplier
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    supplier_no          VARCHAR(32)  NOT NULL,
    owner_id             VARCHAR(32)  NOT NULL,
    platform_supplier_no VARCHAR(32)  DEFAULT NULL,
    name                 VARCHAR(128) NOT NULL,
    short_name           VARCHAR(32)  DEFAULT NULL,
    contact_name         VARCHAR(64)  DEFAULT NULL,
    contact_phone        VARCHAR(32)  DEFAULT NULL,
    status               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    remark               VARCHAR(255) DEFAULT NULL,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by           VARCHAR(64)  DEFAULT NULL,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by           VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sup_no UNIQUE (owner_id, supplier_no),
    CONSTRAINT uk_sup_name UNIQUE (owner_id, name)
);

-- 种子数据
INSERT IGNORE INTO inv_uom (uom_code, name, divisible, sort)
SELECT 'PIECE', '件', 0, 10 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM inv_uom x WHERE x.uom_code = 'PIECE');
INSERT IGNORE INTO inv_uom (uom_code, name, divisible, sort)
SELECT 'BAG', '袋', 0, 20 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM inv_uom x WHERE x.uom_code = 'BAG');
INSERT IGNORE INTO inv_uom (uom_code, name, divisible, sort)
SELECT 'BOX', '箱', 0, 30 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM inv_uom x WHERE x.uom_code = 'BOX');
INSERT IGNORE INTO inv_uom (uom_code, name, divisible, sort)
SELECT 'BOTTLE', '瓶', 0, 40 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM inv_uom x WHERE x.uom_code = 'BOTTLE');
INSERT IGNORE INTO inv_uom (uom_code, name, divisible, sort)
SELECT 'PORTION', '份', 0, 50 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM inv_uom x WHERE x.uom_code = 'PORTION');
INSERT IGNORE INTO inv_uom (uom_code, name, divisible, sort)
SELECT 'JIN', '斤', 1, 60 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM inv_uom x WHERE x.uom_code = 'JIN');
INSERT IGNORE INTO inv_uom (uom_code, name, divisible, sort)
SELECT 'KG', '公斤', 1, 70 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM inv_uom x WHERE x.uom_code = 'KG');
INSERT IGNORE INTO inv_uom (uom_code, name, divisible, sort)
SELECT 'G', '克', 1, 80 FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM inv_uom x WHERE x.uom_code = 'G');
