-- Explicit merchant product policy, absence must never become a permissive default.
CREATE TABLE prd_sku_pricing_policy (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    policy_no VARCHAR(32) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    entity_no VARCHAR(32) NOT NULL,
    store_no VARCHAR(32) NOT NULL,
    goods_no VARCHAR(32) NOT NULL,
    sku_no VARCHAR(32) NOT NULL,
    promotion_eligible TINYINT NOT NULL,
    tax_class VARCHAR(24) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    created_by VARCHAR(32) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_sku_pricing_policy_no (policy_no),
    UNIQUE KEY uk_sku_pricing_policy_scope (tenant_no, entity_no, store_no, sku_no),
    CONSTRAINT ck_sku_pricing_policy_flag CHECK (promotion_eligible IN (0, 1)),
    CONSTRAINT ck_sku_pricing_policy_tax CHECK (tax_class IN ('STANDARD', 'ZERO_RATED', 'EXEMPT', 'OUT_OF_SCOPE')),
    CONSTRAINT ck_sku_pricing_policy_version CHECK (version >= 1 AND version <= 9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
