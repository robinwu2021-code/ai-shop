-- ============================================================================
-- 元器件独立库 · 基线（第一步：料号查询 · 供应商入驻与上传 · 平台询价）
--
-- 这是**另一个数据库**（ai_shop_elec）的第一条迁移，与 ai_shop 的 Flyway 历史互不知情。
-- 设计见 docs/technical/design/TDD-元器件-数据库设计.md；第一步只建 13 张，
-- 派单 / 报价 / 加价规则 / 成交跟进等到平台不再手工处理询价时再建。
--
-- 全库通则（逐表不再重复）：
--   ① 无 tenant_no；隔离维度是 supplier_no / buyer_ref
--   ② 无 deleted 软删：主数据 status=ARCHIVED/MERGED，单据 status=CANCELLED
--   ③ 无外键，业务键 VARCHAR(32) 关联
--   ④ 单价 *_e6 BIGINT（百万分之一元）—— 0402 电阻 ¥0.0015，按分存做不出来
--   ⑤ 每个价都配 tax_included
--   ⑥ 跨库只存外部键：buyer_ref / account_ref = 主系统 usr_no
--   ⑦ 不写 COLLATE，跟随库默认（生产主库已是 MySQL 9.7，uca1400 不可用）
--
-- 表前缀 elc_。不用 prd_ / mch_ —— 独立之后对方库里不会有那些东西。
-- ============================================================================


-- ─────────────────────────────────────────────────────────────────────────────
-- A. 料号库
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS elc_manufacturer
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    mfr_code     VARCHAR(32)  NOT NULL COMMENT '厂牌业务键，如 TI / ST',
    name_en      VARCHAR(128) NOT NULL,
    name_cn      VARCHAR(64)  DEFAULT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / MERGED',
    merged_into  VARCHAR(32)  DEFAULT NULL COMMENT '被收购合并到哪个 mfr_code',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(64)  DEFAULT NULL,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by   VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_elc_mfr (mfr_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='厂牌';

-- 厂牌别名：搜索与上传匹配的命中率靠它。一个写法只能指一家（UK 在 alias_norm 上）；
-- 有歧义的写法（如 ON）不进这张表，匹配时落到「厂牌未确认」
CREATE TABLE IF NOT EXISTS elc_mfr_alias
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    alias_norm   VARCHAR(128) NOT NULL COMMENT '规范化后的别名：大写、去空白与标点',
    mfr_code     VARCHAR(32)  NOT NULL,
    source       VARCHAR(16)  NOT NULL DEFAULT 'SEED' COMMENT 'SEED 初始 / OPS 运营加',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_elc_mfr_alias (alias_norm)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='厂牌别名';

-- 料号。第一步由供应商上传长出来（source=UPLOAD, status=PENDING），PENDING 的买家照样搜得到 ——
-- 等运营审完才可见，供应商传完就会「搜不到自己的货」
CREATE TABLE IF NOT EXISTS elc_part
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    part_no        VARCHAR(32)  NOT NULL,
    mpn            VARCHAR(64)  NOT NULL COMMENT '原样展示',
    mpn_norm       VARCHAR(64)  NOT NULL COMMENT '规范化料号：大写，只留字母数字与 / . # + ,；前缀检索走它',
    mfr_code       VARCHAR(32)  NOT NULL COMMENT '厂牌不明时为 UNKNOWN',
    mfr_name_raw   VARCHAR(64)  DEFAULT NULL COMMENT 'UNKNOWN 时第一次上传写的厂牌原文，展示用',
    pkg            VARCHAR(32)  DEFAULT NULL COMMENT '封装，如 LQFP-48 / 0402。列名不叫 package：那个词在几种库里是保留字，且与实体字段对不上时对齐守卫看不出来',
    description    VARCHAR(255) DEFAULT NULL,
    source         VARCHAR(16)  NOT NULL DEFAULT 'UPLOAD' COMMENT 'UPLOAD / OPS',
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'ACTIVE / PENDING 待运营过目 / MERGED',
    merged_into    VARCHAR(32)  DEFAULT NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     VARCHAR(64)  DEFAULT NULL,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by     VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_elc_part (part_no),
    -- 同一个料号串可以属于两家厂牌（真实存在），所以唯一键带上厂牌
    UNIQUE KEY uk_elc_part_mfr_mpn (mfr_code, mpn_norm),
    KEY idx_elc_part_mpn (mpn_norm)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='料号';


-- 料号的「分段键」：让中段也能搜到。
--
-- 采购常常只记得中间一截（F103C8、C8T6、5433），而 elc_part.mpn_norm 上只能做前缀匹配。
-- 做法是把料号在「字母 ↔ 数字」的交界处切开，每个切点起的后缀存一行：
--   STM32F103C8T6 → STM32F103C8T6 / 32F103C8T6 / F103C8T6 / 103C8T6 / C8T6 / 8T6 / T6
-- 于是「中段匹配」变成了对 key_norm 的前缀匹配，照样走 B-tree。
-- 只在交界处切而不是每个字符都切：每个料号 6～10 行而不是 60 行，百万料号也就千万行级。
-- 少于 2 个字符的后缀不存（单个「6」搜出来没有意义）
CREATE TABLE IF NOT EXISTS elc_part_key
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    key_norm    VARCHAR(64) NOT NULL COMMENT '规范化料号从某个字母/数字交界处起的后缀',
    part_no     VARCHAR(32) NOT NULL,
    pos         SMALLINT    NOT NULL COMMENT '从第几个字符起切的（0 = 整个料号）；排序时越靠前越相关',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_elc_part_key (key_norm, part_no),
    KEY idx_elc_part_key_part (part_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='料号分段键（中段搜索）';


-- ─────────────────────────────────────────────────────────────────────────────
-- B. 供应商（独立主体，不复用 mch_entity）
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS elc_supplier
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    supplier_no    VARCHAR(32)  NOT NULL,
    company_name   VARCHAR(128) DEFAULT NULL COMMENT '只在供应商面与平台面出现，买家永远看不到。点一下就成为供应商，所以可以先空着，之后补',
    kind           VARCHAR(16)  NOT NULL DEFAULT 'TRADER' COMMENT 'AGENT 代理 / TRADER 贸易 / FACTORY 工厂余料 / OTHER',
    city           VARCHAR(32)  DEFAULT NULL COMMENT '所在城市，供运营找货时判断远近',
    contact_name   VARCHAR(32)  DEFAULT NULL,
    contact_phone  VARCHAR(32)  NOT NULL COMMENT '联系手机，默认是入驻人绑定的号，可改',
    mask_code      VARCHAR(8)   NOT NULL COMMENT '匿名代号 S-XXXX，入驻即生成、永不复用',
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / SUSPENDED。第一步入驻即可用，平台事后看',
    notified_at    DATETIME     DEFAULT NULL COMMENT '入驻通知送达企业微信的时间；空 = 没送到',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     VARCHAR(64)  DEFAULT NULL,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by     VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_elc_supplier (supplier_no),
    UNIQUE KEY uk_elc_supplier_mask (mask_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商';

-- 谁能代表这家供应商。一个人第一步只代表一家（UK 在 account_ref 上）
CREATE TABLE IF NOT EXISTS elc_supplier_member
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    supplier_no  VARCHAR(32)  NOT NULL,
    account_ref  VARCHAR(32)  NOT NULL COMMENT '主系统 usr_no；独立之后换成本域账号',
    role         VARCHAR(16)  NOT NULL DEFAULT 'OWNER' COMMENT 'OWNER / STAFF',
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / REMOVED',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(64)  DEFAULT NULL,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by   VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_elc_member_account (account_ref),
    KEY idx_elc_member_supplier (supplier_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商成员';


-- ─────────────────────────────────────────────────────────────────────────────
-- C. 库存
-- ─────────────────────────────────────────────────────────────────────────────

-- 一次上传。先算后做：解析与匹配只写 batch + batch_row，确认之后才碰 elc_stock
CREATE TABLE IF NOT EXISTS elc_stock_batch
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    batch_no      VARCHAR(32)  NOT NULL,
    supplier_no   VARCHAR(32)  NOT NULL,
    file_name     VARCHAR(128) DEFAULT NULL,
    mode          VARCHAR(16)  NOT NULL DEFAULT 'MERGE' COMMENT 'MERGE 只改表里有的行 / REPLACE 表里没有的下架',
    tax_included  TINYINT      NOT NULL DEFAULT 1 COMMENT '这张表的价格含不含税',
    headers       TEXT         DEFAULT NULL COMMENT '表头一行，JSON 数组',
    column_map    VARCHAR(512) DEFAULT NULL COMMENT '字段 → 列序号，JSON；下次上传默认沿用',
    row_total     INT          NOT NULL DEFAULT 0,
    row_valid     INT          NOT NULL DEFAULT 0,
    row_invalid   INT          NOT NULL DEFAULT 0,
    to_insert     INT          NOT NULL DEFAULT 0,
    to_update     INT          NOT NULL DEFAULT 0,
    to_delist     INT          NOT NULL DEFAULT 0,
    unchanged     INT          NOT NULL DEFAULT 0,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PARSED' COMMENT 'PARSED 已预览 / APPLIED 已上架',
    applied_at    DATETIME     DEFAULT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(64)  DEFAULT NULL,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by    VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_elc_batch (batch_no),
    KEY idx_elc_batch_supplier (supplier_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存上传批次';

-- 上传原样的每一行。只追加（换列映射时按它重算，不用再传一次文件）
CREATE TABLE IF NOT EXISTS elc_stock_batch_row
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    batch_no    VARCHAR(32) NOT NULL,
    row_idx     INT         NOT NULL COMMENT '表里的行号，从 1 起（含表头）',
    cells       TEXT        NOT NULL COMMENT '原样一行，JSON 数组',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_elc_batch_row (batch_no, row_idx)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传原样行';

-- 库存行：供应商面。买家永远不直接读它（买家读 elc_part_market）
CREATE TABLE IF NOT EXISTS elc_stock
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    stock_no      VARCHAR(32)  NOT NULL,
    supplier_no   VARCHAR(32)  NOT NULL,
    line_key      VARCHAR(160) NOT NULL COMMENT '认行的键：规范化料号|规范化厂牌|批号',
    part_no       VARCHAR(32)  NOT NULL,
    mpn_raw       VARCHAR(64)  NOT NULL,
    mfr_raw       VARCHAR(64)  DEFAULT NULL,
    mpn_norm      VARCHAR(64)  NOT NULL,
    qty           BIGINT       NOT NULL,
    date_code     VARCHAR(16)  DEFAULT NULL COMMENT '原样：2338 / 23+ / 24/25',
    dc_year       SMALLINT     DEFAULT NULL COMMENT '解析出的年份；买家只看得到这个',
    pkg           VARCHAR(32)  DEFAULT NULL COMMENT '供应商写的封装，可能与料号库不同。列名不叫 package，理由同 elc_part',
    moq           INT          DEFAULT NULL,
    price_e6      BIGINT       DEFAULT NULL COMMENT '单价，百万分之一元；空 = 没报价',
    tax_included  TINYINT      NOT NULL DEFAULT 1,
    valid_until   DATE         NOT NULL COMMENT '到期即不再计入买家看到的库存',
    confirmed_at  DATETIME     NOT NULL COMMENT '最近一次上传或「仍有货」',
    status        VARCHAR(16)  NOT NULL DEFAULT 'ON' COMMENT 'ON / DELISTED 全量替换时下架',
    batch_no      VARCHAR(32)  DEFAULT NULL COMMENT '最后一次被哪批改过',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(64)  DEFAULT NULL,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by    VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_elc_stock (stock_no),
    UNIQUE KEY uk_elc_stock_line (supplier_no, line_key),
    KEY idx_elc_stock_part (part_no, status),
    KEY idx_elc_stock_valid (supplier_no, status, valid_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商库存行';

-- 买家面的库存投影：每个有货料号一行。**物理上没有供应商列**，数量与家数只存档位 ——
-- 精确库存加上批号与地区，同行一眼就能认出是谁家的货。
-- next_expiry_at 到了就过期（有库存行到期了），读的时候按它重算，不需要定时任务
CREATE TABLE IF NOT EXISTS elc_part_market
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    part_no         VARCHAR(32) NOT NULL,
    qty_band        VARCHAR(8)  NOT NULL COMMENT 'B1 / B100 / B1K / B10K / B100K / B1M',
    source_band     VARCHAR(8)  NOT NULL COMMENT 'ONE / FEW(2-4) / MANY(5+)',
    price_from_e6   BIGINT      DEFAULT NULL COMMENT '含税参考起价（已按平台规则加价）；空 = 都没报价',
    dc_year_max     SMALLINT    DEFAULT NULL,
    next_expiry_at  DATETIME    NOT NULL COMMENT '最早一行库存到期的时刻；过了就要重算',
    refreshed_at    DATETIME    NOT NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(64) DEFAULT NULL,
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_elc_market (part_no),
    KEY idx_elc_market_expiry (next_expiry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='买家面库存投影';


-- ─────────────────────────────────────────────────────────────────────────────
-- D. 询价（第一步：平台收单、企业微信通知、线下跟进）
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS elc_rfq
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    rfq_no         VARCHAR(32)  NOT NULL,
    buyer_ref      VARCHAR(32)  NOT NULL COMMENT '主系统 usr_no',
    contact_phone  VARCHAR(32)  NOT NULL COMMENT '提交那一刻绑定的手机号（快照）',
    contact_name   VARCHAR(32)  DEFAULT NULL,
    company        VARCHAR(128) DEFAULT NULL,
    need_invoice   VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE / VAT_NORMAL 普票 / VAT_SPECIAL 专票',
    dc_req         VARCHAR(8)   NOT NULL DEFAULT 'ANY' COMMENT 'ANY / Y1 一年内 / Y2 两年内',
    deliver_city   VARCHAR(32)  DEFAULT NULL,
    remark         VARCHAR(255) DEFAULT NULL,
    line_cnt       INT          NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL DEFAULT 'SUBMITTED' COMMENT 'SUBMITTED 待报价 / QUOTED 已报价 / ACCEPTED 买家已接受 / CLOSED 已结束。过期不落库：QUOTED 且过了有效期即显示为过期',
    notified_at    DATETIME     DEFAULT NULL COMMENT '新询价送达企业微信的时间；空 = 没送到',
    quoted_at      DATETIME     DEFAULT NULL,
    quoted_by      VARCHAR(64)  DEFAULT NULL COMMENT '录入报价的运营',
    quote_valid_until DATE      DEFAULT NULL COMMENT '报价有效到哪天（含）',
    quote_note     VARCHAR(255) DEFAULT NULL COMMENT '平台给买家的说明',
    buyer_notified_at DATETIME  DEFAULT NULL COMMENT '结果通知送达买家的时间（订阅消息或站内信任一送到）；空 = 没送到',
    accepted_at    DATETIME     DEFAULT NULL,
    closed_at      DATETIME     DEFAULT NULL,
    close_reason   VARCHAR(16)  DEFAULT NULL COMMENT 'NO_SOURCE 暂无货源 / BUYER_CANCELLED 买家不要了 / DONE 已成交',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     VARCHAR(64)  DEFAULT NULL,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by     VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_elc_rfq (rfq_no),
    KEY idx_elc_rfq_buyer (buyer_ref, created_at),
    KEY idx_elc_rfq_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='询价单';

CREATE TABLE IF NOT EXISTS elc_rfq_line
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    rfq_no          VARCHAR(32) NOT NULL,
    line_no         INT         NOT NULL,
    part_no         VARCHAR(32) DEFAULT NULL COMMENT '料号库里有才有',
    mpn_raw         VARCHAR(64) NOT NULL,
    mfr_raw         VARCHAR(64) DEFAULT NULL,
    qty             BIGINT      NOT NULL,
    target_e6       BIGINT      DEFAULT NULL COMMENT '目标单价，百万分之一元',
    -- 平台报价（第一步一单一个报价方：平台）。quote_e6 为空 = 这一行没报（没找到货）
    quote_e6        BIGINT      DEFAULT NULL COMMENT '含税单价，百万分之一元',
    quote_qty       BIGINT      DEFAULT NULL COMMENT '可供数量',
    quote_dc_year   SMALLINT    DEFAULT NULL COMMENT '批次年份（买家只看年份）',
    quote_lead_days SMALLINT    DEFAULT NULL COMMENT '交期天数，0 = 现货',
    quote_note      VARCHAR(128) DEFAULT NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(64) DEFAULT NULL,
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_elc_rfq_line (rfq_no, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='询价行';


-- ─────────────────────────────────────────────────────────────────────────────
-- E. 搜索需求（按天聚合，不记是谁搜的）
-- ─────────────────────────────────────────────────────────────────────────────

-- 两个用途：①「搜了没结果」的料号就是平台该去找的货，也是拉新供应商的话术；
-- ② 首页「这周询价最多」、将来给供应商看的需求热度。只存聚合，不存人
CREATE TABLE IF NOT EXISTS elc_search_daily
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    stat_date   DATE        NOT NULL,
    keyword     VARCHAR(64) NOT NULL COMMENT '规范化之后的检索词',
    search_cnt  INT         NOT NULL DEFAULT 0,
    zero_cnt    INT         NOT NULL DEFAULT 0 COMMENT '其中一条结果都没有的次数',
    stock_cnt   INT         NOT NULL DEFAULT 0 COMMENT '其中第一条结果有货的次数',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(64) DEFAULT NULL,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by  VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_elc_search_daily (stat_date, keyword)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='搜索需求日聚合';


-- ─────────────────────────────────────────────────────────────────────────────
-- 种子：常见厂牌与别名。别名一律是规范化之后的写法（大写、去空白与标点）。
-- AMS 刻意不进别名：ams-OSRAM 与 Advanced Monolithic Systems 都写 AMS，AMS1117 只是后者
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO elc_manufacturer (mfr_code, name_en, name_cn) VALUES
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

INSERT INTO elc_mfr_alias (alias_norm, mfr_code) VALUES
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
