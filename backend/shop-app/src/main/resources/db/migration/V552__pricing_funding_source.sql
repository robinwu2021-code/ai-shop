-- Durable quote journal only. No authorization, reservation or asset execution is admitted by this table.
CREATE TABLE ord_pricing_funding_source (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    funding_source_no VARCHAR(32) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    quote_no VARCHAR(32) NOT NULL,
    entity_no VARCHAR(32) NOT NULL,
    store_no VARCHAR(32) NOT NULL,
    user_no VARCHAR(32) NOT NULL,
    currency CHAR(3) NOT NULL,
    amount_minor BIGINT NOT NULL,
    economics_hash CHAR(64) NOT NULL,
    preparation_hash CHAR(64) NOT NULL,
    snapshot_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    cancel_reason VARCHAR(128) NULL,
    created_at DATETIME(3) NOT NULL,
    created_by VARCHAR(32) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_pricing_funding_source_no (funding_source_no),
    UNIQUE KEY uk_pricing_funding_source_quote (tenant_no, quote_no),
    CONSTRAINT ck_pricing_funding_source_amount CHECK (amount_minor >= 0),
    CONSTRAINT ck_pricing_funding_source_state CHECK (
        (status = 'PENDING' AND cancel_reason IS NULL) OR (status = 'CANCELLED' AND cancel_reason IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
