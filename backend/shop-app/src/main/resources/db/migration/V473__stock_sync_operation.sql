-- Durable operation identity and result for merchant initiated stock reconciliation.
-- The write itself remains idempotent through prd_stock_sync_log(source_ref, store_no, sku_no).
CREATE TABLE IF NOT EXISTS prd_stock_sync_operation
(
    operation_no VARCHAR(96) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    sku_no VARCHAR(64) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL COMMENT 'PROCESSING / WAITING / FAILED / BLOCKED / COMPLETED',
    result_json JSON DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (operation_no),
    KEY idx_stock_sync_operation_store (entity_no, store_no, updated_at),
    KEY idx_stock_sync_operation_status (status, updated_at)
) COMMENT='Merchant stock reconciliation operation and recoverable result';
