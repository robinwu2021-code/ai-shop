-- Core durable organization fence, not an accounting or manual store whitelist.
ALTER TABLE mch_brand ADD COLUMN stored_value_status VARCHAR(24) NOT NULL DEFAULT 'NONE';
CREATE TABLE mch_brand_financial_activation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL,
 activation_no VARCHAR(64) NOT NULL,
 management_no VARCHAR(64) NOT NULL,
 brand_no VARCHAR(64) NOT NULL,
 issuer_no VARCHAR(64) NOT NULL,
 source_revision BIGINT NOT NULL,
 command_key VARCHAR(64) NOT NULL,
 command_hash CHAR(64) NOT NULL,
 source_snapshot JSON NOT NULL,
 status VARCHAR(24) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 deleted TINYINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 created_by VARCHAR(64) NOT NULL,
 updated_by VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_brand_financial_activation(tenant_no,activation_no),
 UNIQUE KEY uk_brand_financial_subject(tenant_no,brand_no),
 CHECK (status IN ('PREPARING','ACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;
