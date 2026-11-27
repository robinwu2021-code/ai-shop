ALTER TABLE pmt_coupon ADD COLUMN template_version_no VARCHAR(64) NULL;

CREATE TABLE pmt_coupon_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    coupon_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    version_no VARCHAR(64) NOT NULL,
    rule_snapshot JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_coupon_version (tenant_no, version_no),
    KEY idx_pmt_coupon_version (tenant_no, coupon_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Core 商家券不可变模板版本';
