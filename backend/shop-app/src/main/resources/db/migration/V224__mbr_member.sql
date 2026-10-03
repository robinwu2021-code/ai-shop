-- 会员：一个人与一家商家主体的关系（P1）。
--
-- 会员挂**主体**不挂门店：同一个人在总店买米、在南门店买油，是同一个人，
-- 标签也该是同一份。门店维度另存一张 mbr_member_store —— 因为多店商家真的会问
-- 「南门店有多少熟客」，尤其是两家店隔着十公里的时候。
--
-- 两级指标都由订单派生，唯一真源是 ord_sub_order；这两张表是查询用的缓存，
-- 夜里全量重算兜底，对不上以订单为准。**单店主体不写门店行** —— 那一行等于主表的复制。
CREATE TABLE IF NOT EXISTS mbr_setting
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    member_scope VARCHAR(16) NOT NULL DEFAULT 'ENTITY' COMMENT 'ENTITY 按主体（默认）/ STORE 按门店。只改展示与分层口径，不改存储，可随时切',
    auto_join_on_order TINYINT(4) NOT NULL DEFAULT 1 COMMENT '下单即入会',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_setting_entity (tenant_no, entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='会员经营口径：按主体还是按门店';

CREATE TABLE IF NOT EXISTS mbr_member
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    member_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL COMMENT '商家主体。会员挂主体 —— 同一个人在三家店不该各算一次',
    person_no VARCHAR(64) NOT NULL COMMENT '平台人档（usr_person）。**不存 user_no、不存手机号** —— 那两样都从人档取',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'LEAD 线索（不可触达、不进受众）/ ACTIVE / BLOCKED 商家拉黑',
    source VARCHAR(16) NOT NULL COMMENT '首次来源 ORDER/SHARE/SCAN/MANUAL/FAVORITE/SEARCH。每一次的明细在 mbr_member_source',
    first_store_no VARCHAR(64) DEFAULT NULL COMMENT '从哪家门店进来的。冗余自来源明细，列表不回表',
    first_order_at BIGINT(20) DEFAULT NULL,
    last_order_at BIGINT(20) DEFAULT NULL,
    order_count INT(11) NOT NULL DEFAULT 0,
    total_spent_minor BIGINT(20) NOT NULL DEFAULT 0,
    d90_order_count INT(11) NOT NULL DEFAULT 0 COMMENT '近 90 天单数，每日重算。分层与筛选读它，不现算',
    d90_spent_minor BIGINT(20) NOT NULL DEFAULT 0,
    level VARCHAR(16) DEFAULT NULL COMMENT 'NEW/REGULAR/LOYAL/SLEEPING，主体级。按门店经营时展示 mbr_member_store.level',
    reach_opt_out TINYINT(4) NOT NULL DEFAULT 0 COMMENT '买家关掉了这家店的消息。商家看得到状态，看不到原因',
    remark VARCHAR(255) DEFAULT NULL COMMENT '商家备注（「三单元张阿姨」）',
    joined_at BIGINT(20) NOT NULL,
    claimed_at BIGINT(20) DEFAULT NULL COMMENT '线索被本人认领的时刻',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_member_no (member_no),
    UNIQUE KEY uk_mbr_member_person (tenant_no, entity_no, person_no),
    KEY idx_mbr_member_last (entity_no, last_order_at),
    KEY idx_mbr_member_level (entity_no, level),
    KEY idx_mbr_member_store (entity_no, first_store_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='会员：一个人与一家主体的关系';

CREATE TABLE IF NOT EXISTS mbr_member_store
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    member_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL COMMENT '冗余：按主体+门店筛时不回表',
    store_no VARCHAR(64) NOT NULL,
    first_order_at BIGINT(20) DEFAULT NULL,
    last_order_at BIGINT(20) DEFAULT NULL,
    order_count INT(11) NOT NULL DEFAULT 0,
    total_spent_minor BIGINT(20) NOT NULL DEFAULT 0,
    d90_order_count INT(11) NOT NULL DEFAULT 0,
    d90_spent_minor BIGINT(20) NOT NULL DEFAULT 0,
    level VARCHAR(16) DEFAULT NULL COMMENT '这家店自己的分层。按门店经营时展示的是它',
    is_first_store TINYINT(4) NOT NULL DEFAULT 0 COMMENT '他是从这家店进来的',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_member_store (tenant_no, member_no, store_no),
    KEY idx_mbr_store_last (entity_no, store_no, last_order_at),
    KEY idx_mbr_store_level (entity_no, store_no, level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='会员在某家门店的往来与分层。单店主体不写这张表';

CREATE TABLE IF NOT EXISTS mbr_member_source
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    source_no VARCHAR(64) NOT NULL,
    member_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    source_type VARCHAR(16) NOT NULL COMMENT 'ORDER/SHARE/SCAN/MANUAL/FAVORITE/SEARCH',
    store_no VARCHAR(64) DEFAULT NULL COMMENT '这一次是从哪家门店进来的',
    link_no VARCHAR(64) DEFAULT NULL COMMENT '哪一条分享链接 / 店铺码，可回查落地页',
    ref_no VARCHAR(64) DEFAULT NULL COMMENT '这一次来源对应的单据：ORDER 时是子订单号。**幂等靠它** —— 支付回调会重发',
    inviter_user_no VARCHAR(64) DEFAULT NULL COMMENT '谁发的链接。分享激励结算读它 —— 只记「来自分享」就没法结算',
    inviter_role VARCHAR(16) DEFAULT NULL COMMENT 'MERCHANT 商家 / STAFF 员工 / CUSTOMER 老客转发',
    operator_no VARCHAR(64) DEFAULT NULL COMMENT 'MANUAL 时哪个员工录的。录错了要找得到人',
    activity_no VARCHAR(64) DEFAULT NULL COMMENT '因哪场活动进来的',
    is_first TINYINT(4) NOT NULL DEFAULT 0,
    occurred_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_source_no (source_no),
    KEY idx_mbr_source_member (member_no, occurred_at),
    KEY idx_mbr_source_inviter (entity_no, inviter_user_no, occurred_at),
    KEY idx_mbr_source_activity (activity_no),
    KEY idx_mbr_source_store (entity_no, store_no, occurred_at),
    KEY idx_mbr_source_ref (entity_no, ref_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='会员来源明细：哪家店、哪条链接、谁发的、谁录的、因哪场活动';
