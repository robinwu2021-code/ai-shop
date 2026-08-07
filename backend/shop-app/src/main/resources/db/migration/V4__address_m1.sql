-- M1.3 地址簿（R1 缺口）。设计见 db-design.md §4.1。
-- 「默认地址至多一条」是应用层保证：设新默认时先清旧。
-- 不用唯一索引 (user_no, is_default) —— 那样只能有一条 is_default=0 的地址，语义完全错。

CREATE TABLE IF NOT EXISTS usr_address
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    address_id VARCHAR(64)  NOT NULL,
    user_no    VARCHAR(64)  NOT NULL,
    name       VARCHAR(64)  NOT NULL COMMENT '收件人',
    phone      VARCHAR(32)  NOT NULL COMMENT '一期明文存储，出参按视角脱敏（db-design §6）',
    province   VARCHAR(64)   NULL,
    city       VARCHAR(64)   NULL,
    district   VARCHAR(64)   NULL,
    detail     VARCHAR(255) NOT NULL COMMENT '门牌等详细地址',
    lat_e6     INT           NULL COMMENT '配送范围校验用',
    lng_e6     INT           NULL,
    is_default TINYINT      NOT NULL DEFAULT 0,
    tag        VARCHAR(16)   NULL COMMENT '家/公司/其他',
    tenant_no  VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at DATETIME     NOT NULL,
    created_by VARCHAR(64)   NULL,
    updated_at DATETIME     NOT NULL,
    updated_by VARCHAR(64)   NULL,
    version    BIGINT       NOT NULL DEFAULT 0,
    deleted    TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_address_id (address_id),
    KEY idx_user_default (user_no, is_default)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '地址簿';
