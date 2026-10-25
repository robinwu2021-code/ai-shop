-- Original frozen item allocations remain immutable; these append-only adjustments change unpaid debt.
CREATE TABLE ord_industry_amendment (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',entity_no VARCHAR(32) NOT NULL,
 store_no VARCHAR(32) NOT NULL,order_no VARCHAR(32) NOT NULL,sub_order_no VARCHAR(32) NOT NULL,
 amendment_no VARCHAR(32) NOT NULL,operation_key VARCHAR(64) NOT NULL,request_hash VARCHAR(64) NOT NULL,
 kind VARCHAR(16) NOT NULL,source_return_no VARCHAR(32) NULL,source_hash VARCHAR(64) NOT NULL,
 command_snapshot JSON NOT NULL,evidence_snapshot JSON NULL,inventory_snapshot JSON NULL,
 status VARCHAR(24) NOT NULL,actor_no VARCHAR(32) NOT NULL,expected_sub_version BIGINT NOT NULL,
 currency VARCHAR(3) NOT NULL,receivable_delta_minor BIGINT NOT NULL DEFAULT 0,replacement_cart_no VARCHAR(32) NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,updated_by VARCHAR(32) NULL,
 UNIQUE KEY uk_industry_amendment_no(amendment_no),UNIQUE KEY uk_industry_amendment_key(tenant_no,entity_no,operation_key),
 KEY ix_industry_amendment_recovery(status,updated_at),KEY ix_industry_amendment_sub(sub_order_no,status)
);
CREATE TABLE ord_industry_amendment_line (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,amendment_no VARCHAR(32) NOT NULL,order_item_id BIGINT NOT NULL,
 source_component_ref VARCHAR(64) NOT NULL,quantity DECIMAL(24,6) NOT NULL,physical_reduction_qty DECIMAL(24,6) NOT NULL,
 cancel_qty DECIMAL(24,6) NOT NULL,unit VARCHAR(16) NOT NULL,disposition VARCHAR(16) NOT NULL,
 receivable_reduction_minor BIGINT NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,created_by VARCHAR(32) NOT NULL,
 UNIQUE KEY uk_industry_amendment_line(amendment_no,order_item_id,source_component_ref)
);
CREATE TABLE ord_industry_receivable_adjustment (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',entity_no VARCHAR(32) NOT NULL,
 store_no VARCHAR(32) NOT NULL,order_no VARCHAR(32) NOT NULL,sub_order_no VARCHAR(32) NOT NULL,
 amendment_no VARCHAR(32) NOT NULL,currency VARCHAR(3) NOT NULL,amount_minor BIGINT NOT NULL,
 source_hash VARCHAR(64) NOT NULL,reason VARCHAR(255) NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_by VARCHAR(32) NOT NULL,UNIQUE KEY uk_receivable_amendment(amendment_no),KEY ix_receivable_order(order_no,id)
);

ALTER TABLE ord_industry_amendment ADD net_delta_minor BIGINT NULL,ADD tax_delta_minor BIGINT NULL;
ALTER TABLE ord_industry_receivable_adjustment ADD net_delta_minor BIGINT NULL,ADD tax_delta_minor BIGINT NULL;

CREATE TABLE ord_industry_fee_line_adjustment (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,amendment_no VARCHAR(32) NOT NULL,order_item_id BIGINT NOT NULL,
 source_component_ref VARCHAR(64) NOT NULL,amount_minor BIGINT NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_by VARCHAR(32) NOT NULL,UNIQUE KEY uk_fee_line_adjustment(amendment_no,order_item_id)
);

ALTER TABLE ord_industry_amendment_line ADD net_reduction_minor BIGINT NOT NULL,ADD tax_reduction_minor BIGINT NOT NULL;
