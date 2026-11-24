-- 行业后付单的人工实收确认（TDD-餐饮柜台后付结账 · ADR-059）。
-- 柜台用现金或外部刷卡机收齐一张结算单后，核心核验外部记账、在一个事务里把结算单覆盖的后付单推到 PAID。
-- 编号避开实时推送分支已占用的 V661–V664，合并时再核对。
-- 订单表只改状态与线下确认人、确认时间（与商家「确认线下收款」同一写法），不动 receipt_provenance 与其约束；
-- 「这是后付人工实收」记在本迁移的三张表里。

CREATE TABLE ord_postpay_receipt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    receipt_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    entity_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    store_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    biz_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    currency CHAR(3) NOT NULL,
    due_minor BIGINT NOT NULL,
    received_minor BIGINT NOT NULL,
    change_minor BIGINT NOT NULL,
    request_key_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
    payload_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
    actor_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    receipt_provenance VARCHAR(24) NOT NULL,
    received_at DATETIME(3) NOT NULL,
    recorded_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    created_by VARCHAR(64) NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_postpay_receipt_no (tenant_no,receipt_no),
    UNIQUE KEY uk_postpay_receipt_biz (tenant_no,entity_no,store_no,biz_no),
    UNIQUE KEY uk_postpay_receipt_key (tenant_no,entity_no,store_no,request_key_hash),
    CONSTRAINT ck_postpay_receipt_amounts CHECK (due_minor > 0 AND received_minor >= due_minor
        AND change_minor = received_minor - due_minor),
    CONSTRAINT ck_postpay_receipt_provenance CHECK (receipt_provenance = 'POSTPAY_RECEIPT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行业后付人工实收（一张结算单一行）';

CREATE TABLE ord_postpay_receipt_root (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    receipt_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    order_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    due_minor BIGINT NOT NULL,
    core_version VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_postpay_root_order (tenant_no,order_no),
    KEY ix_postpay_root_receipt (tenant_no,receipt_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实收覆盖的后付核心单；一张核心单只能被一次实收覆盖';

CREATE TABLE ord_postpay_receipt_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    receipt_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    record_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    method VARCHAR(24) NOT NULL,
    amount_minor BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_postpay_record (tenant_no,record_no),
    KEY ix_postpay_record_receipt (tenant_no,receipt_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实收引用的外部记账；一笔记账只能用于一次实收';
