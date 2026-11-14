-- An original channel cancellation can precede the first native restaurant source; no party/cart/batch is invented.
CREATE TABLE fnb_channel_import_guard (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    entity_no VARCHAR(32) NOT NULL,
    store_no VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    created_by VARCHAR(32) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted INT NOT NULL DEFAULT 0,
    guard_no VARCHAR(32) NOT NULL,
    import_no VARCHAR(32) NOT NULL,
    original_document_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    withdrawal_snapshot LONGTEXT NULL,
    core_cancellation_snapshot LONGTEXT NULL,
    cancellation_receipt_ref VARCHAR(128) NULL,
    UNIQUE KEY uk_fnb_channel_import_guard_no(guard_no),
    UNIQUE KEY uk_fnb_channel_import_guard(tenant_no,entity_no,store_no,import_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
