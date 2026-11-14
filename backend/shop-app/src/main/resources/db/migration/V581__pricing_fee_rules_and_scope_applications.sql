-- CORE fee authority: versioned definitions, original scope applications and audited correction advice.
CREATE TABLE ord_pricing_fee_rule (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',entity_no VARCHAR(32) NOT NULL,store_no VARCHAR(32) NOT NULL,
 rule_no VARCHAR(32) NOT NULL,revision_no VARCHAR(32) NOT NULL,status VARCHAR(16) NOT NULL,
 version BIGINT NOT NULL,updated_at DATETIME(3) NOT NULL,updated_by VARCHAR(32) NOT NULL,
 UNIQUE KEY uk_pricing_fee_rule (entity_no,store_no,rule_no),CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ord_pricing_fee_revision (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',entity_no VARCHAR(32) NOT NULL,store_no VARCHAR(32) NOT NULL,
 rule_no VARCHAR(32) NOT NULL,revision_no VARCHAR(32) NOT NULL,definition_snapshot JSON NOT NULL,
 request_key CHAR(64) NOT NULL,request_hash CHAR(64) NOT NULL,actor_no VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL,reason VARCHAR(255) NOT NULL,created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_pricing_fee_revision (revision_no),UNIQUE KEY uk_pricing_fee_rule_command (entity_no,store_no,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ord_pricing_fee_scope (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',entity_no VARCHAR(32) NOT NULL,store_no VARCHAR(32) NOT NULL,
 scope_no VARCHAR(32) NOT NULL,rule_no VARCHAR(32) NOT NULL,currency CHAR(3) NOT NULL,
 goods_basis_minor BIGINT NOT NULL,fee_minor BIGINT NOT NULL,version BIGINT NOT NULL,
 UNIQUE KEY uk_pricing_fee_scope (entity_no,store_no,scope_no,rule_no),
 CHECK (goods_basis_minor>=0 AND fee_minor>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ord_pricing_fee_application (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',entity_no VARCHAR(32) NOT NULL,store_no VARCHAR(32) NOT NULL,
 application_no VARCHAR(32) NOT NULL,order_no VARCHAR(32) NOT NULL,sub_order_no VARCHAR(32) NOT NULL,quote_no VARCHAR(32) NOT NULL,
 rule_no VARCHAR(32) NOT NULL,revision_no VARCHAR(32) NOT NULL,scope_no VARCHAR(32) NOT NULL,currency CHAR(3) NOT NULL,
 original_goods_basis_minor BIGINT NOT NULL DEFAULT 0,current_goods_basis_minor BIGINT NOT NULL DEFAULT 0,current_quantity DECIMAL(20,6) NOT NULL DEFAULT 0,
 fee_minor BIGINT NOT NULL,adjusted_fee_minor BIGINT NOT NULL,source_hash CHAR(64) NOT NULL,created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_pricing_fee_application (application_no),UNIQUE KEY uk_pricing_fee_quote_scope (entity_no,store_no,quote_no,rule_no,scope_no),
 KEY ix_pricing_fee_original_order (entity_no,store_no,order_no,sub_order_no),
 CHECK (fee_minor>=0 AND adjusted_fee_minor>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ord_pricing_fee_advice (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',entity_no VARCHAR(32) NOT NULL,store_no VARCHAR(32) NOT NULL,
 application_no VARCHAR(32) NOT NULL,quote_no VARCHAR(32) NOT NULL,current_fee_minor BIGINT NOT NULL,target_fee_minor BIGINT NOT NULL,
 source_hash CHAR(64) NOT NULL,status VARCHAR(16) NOT NULL,
 UNIQUE KEY uk_pricing_fee_advice (entity_no,store_no,application_no,source_hash),
 CHECK (status IN ('OPEN','APPLIED','SUPERSEDED')),CHECK (target_fee_minor>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE ord_order ADD COLUMN fee_minor BIGINT NULL COMMENT 'Shared industry business fees, independent of commission service fee';
ALTER TABLE ord_sub_order ADD COLUMN fee_minor BIGINT NULL COMMENT 'Shared industry business fees, independent of commission service fee';

CREATE TABLE ord_pricing_fee_reconciliation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,entity_no VARCHAR(32) NOT NULL,store_no VARCHAR(32) NOT NULL,
 application_no VARCHAR(32) NOT NULL,amendment_no VARCHAR(32) NOT NULL,source_hash CHAR(64) NOT NULL,
 before_goods_minor BIGINT NOT NULL,after_goods_minor BIGINT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_fee_reconciliation (entity_no,application_no,amendment_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
