-- 门店零售单收款挂单与付清（TDD-统一订单与开票资料 §2 P2，AC7 / AC8 / AC16）。只加可空列、索引与一张新表，不改已有数据。
-- invoice_timing：建单时 invoice.timing（ON_PAID / MANUAL / LATER），NULL = 跟门店开票设定。
ALTER TABLE ord_order
    ADD COLUMN invoice_timing VARCHAR(16) NULL,
    ALGORITHM=INSTANT;

-- 付清判断按子单号加总现金记录：原表只有按 biz_no 的索引
ALTER TABLE ord_offline_record
    ADD KEY idx_offline_record_order (tenant_no,store_no,order_no),
    ALGORITHM=INPLACE, LOCK=NONE;

-- 收款码 / 付款链接（支付单 PYO…，biz_no = 门店零售单子单号）收款成功后挂单：金额取支付域已确认的实收，
-- 一张支付单只挂一次（唯一键 = 幂等）。现金记录仍在 ord_offline_record，不复制到这里。
CREATE TABLE ord_store_collection (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    pay_order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    sub_order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    entity_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    store_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    currency CHAR(3) NOT NULL,
    paid_minor BIGINT NOT NULL,
    pay_channel VARCHAR(32) NULL,
    created_at DATETIME(3) NOT NULL,
    created_by VARCHAR(64) NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_store_collection_pay (tenant_no,pay_order_no),
    KEY idx_store_collection_sub (tenant_no,sub_order_no),
    CONSTRAINT ck_store_collection_amount CHECK (paid_minor > 0 AND deleted=0)
) ENGINE=InnoDB COMMENT='Collections (pay orders) attached to a store order by biz_no, paid amount from the pay domain';
