-- An additive original-source guard serializes admission against cancellation before order identities exist.
CREATE TABLE ord_channel_admission_guard (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    version BIGINT NOT NULL DEFAULT 0,
    deleted INT NOT NULL DEFAULT 0,
    entity_no VARCHAR(32) NOT NULL,
    store_no VARCHAR(32) NOT NULL,
    source_system VARCHAR(32) NOT NULL,
    source_no VARCHAR(32) NOT NULL,
    source_version BIGINT NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    import_ref VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    withdrawal_snapshot LONGTEXT NULL,
    cancellation_receipt_ref VARCHAR(128) NULL,
    UNIQUE KEY uk_ord_channel_guard(tenant_no,entity_no,store_no,source_system,import_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
