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


CREATE TABLE IF NOT EXISTS elc_manufacturer
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    mfr_code     VARCHAR(32)  NOT NULL,
    name_en      VARCHAR(128) NOT NULL,
    name_cn      VARCHAR(64)  DEFAULT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    merged_into  VARCHAR(32)  DEFAULT NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(64)  DEFAULT NULL,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by   VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_elc_mfr UNIQUE (mfr_code)
);

CREATE TABLE IF NOT EXISTS elc_mfr_alias
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    alias_norm   VARCHAR(128) NOT NULL,
    mfr_code     VARCHAR(32)  NOT NULL,
    source       VARCHAR(16)  NOT NULL DEFAULT 'SEED',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_elc_mfr_alias UNIQUE (alias_norm)
);

CREATE TABLE IF NOT EXISTS elc_part
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    part_no        VARCHAR(32)  NOT NULL,
    mpn            VARCHAR(64)  NOT NULL,
    mpn_norm       VARCHAR(64)  NOT NULL,
    mfr_code       VARCHAR(32)  NOT NULL,
    mfr_name_raw   VARCHAR(64)  DEFAULT NULL,
    pkg            VARCHAR(32)  DEFAULT NULL,
    description    VARCHAR(255) DEFAULT NULL,
    source         VARCHAR(16)  NOT NULL DEFAULT 'UPLOAD',
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    merged_into    VARCHAR(32)  DEFAULT NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     VARCHAR(64)  DEFAULT NULL,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by     VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_elc_part UNIQUE (part_no),
    CONSTRAINT uk_elc_part_mfr_mpn UNIQUE (mfr_code, mpn_norm)
);

CREATE TABLE IF NOT EXISTS elc_part_key
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    key_norm    VARCHAR(64) NOT NULL,
    part_no     VARCHAR(32) NOT NULL,
    pos         SMALLINT    NOT NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_elc_part_key UNIQUE (key_norm, part_no)
);

CREATE TABLE IF NOT EXISTS elc_supplier
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    supplier_no    VARCHAR(32)  NOT NULL,
    company_name   VARCHAR(128) DEFAULT NULL,
    kind           VARCHAR(16)  NOT NULL DEFAULT 'TRADER',
    city           VARCHAR(32)  DEFAULT NULL,
    contact_name   VARCHAR(32)  DEFAULT NULL,
    contact_phone  VARCHAR(32)  NOT NULL,
    mask_code      VARCHAR(8)   NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    notified_at    DATETIME     DEFAULT NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     VARCHAR(64)  DEFAULT NULL,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by     VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_elc_supplier UNIQUE (supplier_no),
    CONSTRAINT uk_elc_supplier_mask UNIQUE (mask_code)
);

CREATE TABLE IF NOT EXISTS elc_supplier_member
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    supplier_no  VARCHAR(32)  NOT NULL,
    account_ref  VARCHAR(32)  NOT NULL,
    role         VARCHAR(16)  NOT NULL DEFAULT 'OWNER',
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(64)  DEFAULT NULL,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by   VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_elc_member_account UNIQUE (account_ref)
);

CREATE TABLE IF NOT EXISTS elc_stock_batch
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    batch_no      VARCHAR(32)  NOT NULL,
    supplier_no   VARCHAR(32)  NOT NULL,
    file_name     VARCHAR(128) DEFAULT NULL,
    mode          VARCHAR(16)  NOT NULL DEFAULT 'MERGE',
    tax_included  TINYINT      NOT NULL DEFAULT 1,
    currency      CHAR(3)      NOT NULL DEFAULT 'CNY',
    headers       TEXT         DEFAULT NULL,
    column_map    VARCHAR(512) DEFAULT NULL,
    tier_cols     VARCHAR(512) DEFAULT NULL,
    row_total     INT          NOT NULL DEFAULT 0,
    row_valid     INT          NOT NULL DEFAULT 0,
    row_invalid   INT          NOT NULL DEFAULT 0,
    to_insert     INT          NOT NULL DEFAULT 0,
    to_update     INT          NOT NULL DEFAULT 0,
    to_delist     INT          NOT NULL DEFAULT 0,
    unchanged     INT          NOT NULL DEFAULT 0,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PARSED',
    applied_at    DATETIME     DEFAULT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(64)  DEFAULT NULL,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by    VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_elc_batch UNIQUE (batch_no)
);

CREATE TABLE IF NOT EXISTS elc_stock_batch_row
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    batch_no    VARCHAR(32) NOT NULL,
    row_idx     INT         NOT NULL,
    cells       TEXT        NOT NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS elc_stock
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    stock_no      VARCHAR(32)  NOT NULL,
    supplier_no   VARCHAR(32)  NOT NULL,
    line_key      VARCHAR(160) NOT NULL,
    part_no       VARCHAR(32)  NOT NULL,
    mpn_raw       VARCHAR(64)  NOT NULL,
    mfr_raw       VARCHAR(64)  DEFAULT NULL,
    mpn_norm      VARCHAR(64)  NOT NULL,
    qty           BIGINT       NOT NULL,
    date_code     VARCHAR(16)  DEFAULT NULL,
    dc_year       SMALLINT     DEFAULT NULL,
    pkg           VARCHAR(32)  DEFAULT NULL,
    price_tiers   TEXT         DEFAULT NULL,
    price_e6      BIGINT       DEFAULT NULL,
    currency      CHAR(3)      NOT NULL DEFAULT 'CNY',
    tax_included  TINYINT      NOT NULL DEFAULT 1,
    moq           INT          DEFAULT NULL,
    spq           INT          DEFAULT NULL,
    packing       VARCHAR(16)  DEFAULT NULL,
    cond_grade    VARCHAR(16)  DEFAULT NULL,
    lead_days     SMALLINT     DEFAULT NULL,
    region        VARCHAR(32)  DEFAULT NULL,
    valid_until   DATE         NOT NULL,
    confirmed_at  DATETIME     NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ON',
    batch_no      VARCHAR(32)  DEFAULT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(64)  DEFAULT NULL,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by    VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_elc_stock UNIQUE (stock_no),
    CONSTRAINT uk_elc_stock_line UNIQUE (supplier_no, line_key)
);

CREATE TABLE IF NOT EXISTS elc_part_market
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    part_no         VARCHAR(32) NOT NULL,
    qty_band        VARCHAR(8)  NOT NULL,
    source_band     VARCHAR(8)  NOT NULL,
    price_from_e6   BIGINT      DEFAULT NULL,
    price_from_qty  BIGINT      DEFAULT NULL,
    dc_year_max     SMALLINT    DEFAULT NULL,
    spot            TINYINT     NOT NULL DEFAULT 0,
    lead_days_min   SMALLINT    DEFAULT NULL,
    cond_set        VARCHAR(64) DEFAULT NULL,
    next_expiry_at  DATETIME    NOT NULL,
    refreshed_at    DATETIME    NOT NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(64) DEFAULT NULL,
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_elc_market UNIQUE (part_no)
);

CREATE TABLE IF NOT EXISTS elc_rfq
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    rfq_no         VARCHAR(32)  NOT NULL,
    buyer_ref      VARCHAR(32)  NOT NULL,
    contact_phone  VARCHAR(32)  NOT NULL,
    contact_name   VARCHAR(32)  DEFAULT NULL,
    company        VARCHAR(128) DEFAULT NULL,
    need_invoice   VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    dc_req         VARCHAR(8)   NOT NULL DEFAULT 'ANY',
    cond_req       VARCHAR(16)  NOT NULL DEFAULT 'ANY',
    packing_req    VARCHAR(16)  NOT NULL DEFAULT 'ANY',
    need_by_days   SMALLINT     DEFAULT NULL,
    allow_alt      TINYINT      NOT NULL DEFAULT 0,
    deliver_city   VARCHAR(32)  DEFAULT NULL,
    remark         VARCHAR(255) DEFAULT NULL,
    line_cnt       INT          NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL DEFAULT 'SUBMITTED',
    notified_at    DATETIME     DEFAULT NULL,
    quoted_at      DATETIME     DEFAULT NULL,
    quoted_by      VARCHAR(64)  DEFAULT NULL,
    quote_valid_until DATE      DEFAULT NULL,
    quote_note     VARCHAR(255) DEFAULT NULL,
    buyer_notified_at DATETIME  DEFAULT NULL,
    accepted_at    DATETIME     DEFAULT NULL,
    closed_at      DATETIME     DEFAULT NULL,
    close_reason   VARCHAR(16)  DEFAULT NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     VARCHAR(64)  DEFAULT NULL,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by     VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_elc_rfq UNIQUE (rfq_no)
);

CREATE TABLE IF NOT EXISTS elc_rfq_line
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    rfq_no          VARCHAR(32) NOT NULL,
    line_no         INT         NOT NULL,
    part_no         VARCHAR(32) DEFAULT NULL,
    mpn_raw         VARCHAR(64) NOT NULL,
    mfr_raw         VARCHAR(64) DEFAULT NULL,
    qty             BIGINT      NOT NULL,
    target_e6       BIGINT      DEFAULT NULL,
    quote_e6        BIGINT      DEFAULT NULL,
    quote_qty       BIGINT      DEFAULT NULL,
    quote_dc_year   SMALLINT    DEFAULT NULL,
    quote_lead_days SMALLINT    DEFAULT NULL,
    quote_cond      VARCHAR(16) DEFAULT NULL,
    quote_packing   VARCHAR(16) DEFAULT NULL,
    quote_note      VARCHAR(128) DEFAULT NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(64) DEFAULT NULL,
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_elc_rfq_line UNIQUE (rfq_no, line_no)
);

CREATE TABLE IF NOT EXISTS elc_search_daily
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    stat_date   DATE        NOT NULL,
    keyword     VARCHAR(64) NOT NULL,
    search_cnt  INT         NOT NULL DEFAULT 0,
    zero_cnt    INT         NOT NULL DEFAULT 0,
    stock_cnt   INT         NOT NULL DEFAULT 0,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(64) DEFAULT NULL,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by  VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_elc_search_daily UNIQUE (stat_date, keyword)
);

-- 种子数据
INSERT IGNORE INTO elc_manufacturer (mfr_code, name_en, name_cn) VALUES
('TI', 'Texas Instruments', '德州仪器'),
('ST', 'STMicroelectronics', '意法半导体'),
('ADI', 'Analog Devices', '亚德诺'),
('NXP', 'NXP Semiconductors', '恩智浦'),
('INFINEON', 'Infineon Technologies', '英飞凌'),
('MICROCHIP', 'Microchip Technology', '微芯'),
('ONSEMI', 'onsemi', '安森美'),
('RENESAS', 'Renesas Electronics', '瑞萨'),
('VISHAY', 'Vishay', '威世'),
('NEXPERIA', 'Nexperia', '安世'),
('ROHM', 'ROHM Semiconductor', '罗姆'),
('TOSHIBA', 'Toshiba', '东芝'),
('MURATA', 'Murata', '村田'),
('TDK', 'TDK', 'TDK'),
('YAGEO', 'Yageo', '国巨'),
('SAMSUNG', 'Samsung Electro-Mechanics', '三星电机'),
('KEMET', 'KEMET', '基美'),
('DIODES', 'Diodes Incorporated', '美台'),
('MAXIM', 'Maxim Integrated', '美信'),
('MPS', 'Monolithic Power Systems', '芯源'),
('GD', 'GigaDevice', '兆易创新'),
('ESPRESSIF', 'Espressif Systems', '乐鑫'),
('WCH', 'WCH', '沁恒'),
('UNISOC', 'UNISOC', '紫光展锐'),
('3PEAK', '3PEAK', '思瑞浦'),
('SGMICRO', 'SG Micro', '圣邦微'),
('WILLSEMI', 'Will Semiconductor', '韦尔'),
('UMW', 'UMW', '友台'),
('AMS', 'Advanced Monolithic Systems', NULL),
('MOLEX', 'Molex', '莫仕'),
('TE', 'TE Connectivity', '泰科'),
('UNIROYAL', 'Uniroyal', '厚声'),
('FH', 'Fenghua Advanced', '风华高科'),
('CJ', 'Jiangsu Changjing', '长电'),
('LRC', 'Leshan Radio', '乐山无线电'),
('UNKNOWN', 'Unknown', '厂牌未确认');
INSERT IGNORE INTO elc_mfr_alias (alias_norm, mfr_code) VALUES
('TI', 'TI'), ('TEXASINSTRUMENTS', 'TI'), ('德州仪器', 'TI'), ('德州', 'TI'), ('BURRBROWN', 'TI'),
('ST', 'ST'), ('STMICROELECTRONICS', 'ST'), ('STMICRO', 'ST'), ('意法半导体', 'ST'), ('意法', 'ST'),
('ADI', 'ADI'), ('ANALOGDEVICES', 'ADI'), ('亚德诺', 'ADI'), ('LINEAR', 'ADI'), ('LINEARTECHNOLOGY', 'ADI'), ('LT', 'ADI'),
('NXP', 'NXP'), ('NXPSEMICONDUCTORS', 'NXP'), ('恩智浦', 'NXP'), ('FREESCALE', 'NXP'),
('INFINEON', 'INFINEON'), ('INFINEONTECHNOLOGIES', 'INFINEON'), ('英飞凌', 'INFINEON'), ('IR', 'INFINEON'), ('CYPRESS', 'INFINEON'),
('MICROCHIP', 'MICROCHIP'), ('MICROCHIPTECHNOLOGY', 'MICROCHIP'), ('微芯', 'MICROCHIP'), ('ATMEL', 'MICROCHIP'),
('ONSEMI', 'ONSEMI'), ('ONSEMICONDUCTOR', 'ONSEMI'), ('安森美', 'ONSEMI'), ('FAIRCHILD', 'ONSEMI'),
('RENESAS', 'RENESAS'), ('RENESASELECTRONICS', 'RENESAS'), ('瑞萨', 'RENESAS'), ('INTERSIL', 'RENESAS'),
('VISHAY', 'VISHAY'), ('威世', 'VISHAY'),
('NEXPERIA', 'NEXPERIA'), ('安世', 'NEXPERIA'), ('安世半导体', 'NEXPERIA'),
('ROHM', 'ROHM'), ('罗姆', 'ROHM'),
('TOSHIBA', 'TOSHIBA'), ('东芝', 'TOSHIBA'),
('MURATA', 'MURATA'), ('村田', 'MURATA'),
('TDK', 'TDK'),
('YAGEO', 'YAGEO'), ('国巨', 'YAGEO'),
('SAMSUNG', 'SAMSUNG'), ('SAMSUNGELECTROMECHANICS', 'SAMSUNG'), ('SEMCO', 'SAMSUNG'), ('三星', 'SAMSUNG'), ('三星电机', 'SAMSUNG'),
('KEMET', 'KEMET'), ('基美', 'KEMET'),
('DIODES', 'DIODES'), ('DIODESINCORPORATED', 'DIODES'), ('美台', 'DIODES'),
('MAXIM', 'MAXIM'), ('MAXIMINTEGRATED', 'MAXIM'), ('美信', 'MAXIM'),
('MPS', 'MPS'), ('MONOLITHICPOWERSYSTEMS', 'MPS'), ('芯源', 'MPS'),
('GD', 'GD'), ('GIGADEVICE', 'GD'), ('兆易', 'GD'), ('兆易创新', 'GD'),
('ESPRESSIF', 'ESPRESSIF'), ('ESPRESSIFSYSTEMS', 'ESPRESSIF'), ('乐鑫', 'ESPRESSIF'),
('WCH', 'WCH'), ('沁恒', 'WCH'),
('UNISOC', 'UNISOC'), ('紫光展锐', 'UNISOC'), ('展锐', 'UNISOC'),
('3PEAK', '3PEAK'), ('思瑞浦', '3PEAK'),
('SGMICRO', 'SGMICRO'), ('圣邦', 'SGMICRO'), ('圣邦微', 'SGMICRO'),
('WILLSEMI', 'WILLSEMI'), ('韦尔', 'WILLSEMI'),
('UMW', 'UMW'), ('友台', 'UMW'),
('ADVANCEDMONOLITHICSYSTEMS', 'AMS'),
('MOLEX', 'MOLEX'), ('莫仕', 'MOLEX'),
('TE', 'TE'), ('TECONNECTIVITY', 'TE'), ('泰科', 'TE'),
('UNIROYAL', 'UNIROYAL'), ('厚声', 'UNIROYAL'),
('FH', 'FH'), ('FENGHUA', 'FH'), ('风华', 'FH'), ('风华高科', 'FH'),
('CJ', 'CJ'), ('长电', 'CJ'), ('长晶', 'CJ'),
('LRC', 'LRC'), ('乐山无线电', 'LRC');
