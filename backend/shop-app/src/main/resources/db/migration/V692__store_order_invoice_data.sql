-- 门店零售单与订单行开票资料（TDD-统一订单与开票资料 §2 P1，ADR-065）。
-- 只加可空列、唯一键与一张新表，不改已有数据；热表加列用 INSTANT，唯一键在线建。
-- line_kind：SKU / CUSTOM（ADJUSTMENT 预留）；tax_class：STANDARD / ZERO_RATED / EXEMPT（NULL = 待补）；
-- invoice_data_source：GOODS / STORE_ITEM / FILLED。四列全空 = 旧订单行，发票来源按原规则处理。
ALTER TABLE ord_item
    ADD COLUMN line_kind VARCHAR(16) NULL,
    ADD COLUMN tax_class VARCHAR(16) NULL,
    ADD COLUMN invoice_name VARCHAR(256) NULL,
    ADD COLUMN invoice_data_source VARCHAR(16) NULL,
    ALGORITHM=INSTANT;

-- client_order_no：端上单号（离线补传与幂等），门店内唯一；channel：建单渠道（POS …）。
ALTER TABLE ord_order
    ADD COLUMN client_order_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN channel VARCHAR(16) NULL,
    ALGORITHM=INSTANT;

ALTER TABLE ord_order
    ADD UNIQUE KEY uk_order_client_no (tenant_no,store_no,client_order_no),
    ALGORITHM=INPLACE, LOCK=NONE;

CREATE TABLE ord_invoice_data_fill (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    fill_no VARCHAR(40) COLLATE utf8mb4_bin NOT NULL,
    order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    sub_order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    entity_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    store_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    line_no INT NOT NULL,
    field_code VARCHAR(16) NOT NULL,
    old_value VARCHAR(256) NULL,
    new_value VARCHAR(256) NULL,
    reason VARCHAR(100) NULL,
    operator_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    request_key_hash CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    created_by VARCHAR(64) NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_invoice_fill_no (tenant_no,fill_no),
    UNIQUE KEY uk_invoice_fill_field (tenant_no,sub_order_no,line_no,field_code),
    KEY idx_invoice_fill_request (tenant_no,sub_order_no,request_key_hash),
    CONSTRAINT ck_invoice_fill_field CHECK (field_code IN ('NAME','TAX_CLASS','BUYER')
        AND ((field_code='BUYER' AND line_no=0) OR (field_code<>'BUYER' AND line_no>0)) AND deleted=0)
) ENGINE=InnoDB COMMENT='Append-only audit of invoice data filled into a store order after creation';
