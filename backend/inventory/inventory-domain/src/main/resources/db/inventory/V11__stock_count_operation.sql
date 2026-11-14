-- Stable idempotency markers for one-item adjustments used by resumable stock alignment.
CREATE TABLE IF NOT EXISTS inv_stock_count_operation
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id VARCHAR(32) NOT NULL,
    operation_no VARCHAR(96) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    count_no VARCHAR(64) DEFAULT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_count_operation (owner_id, operation_no),
    KEY idx_inv_count_operation_count (owner_id, count_no)
) COMMENT='Idempotency markers for retryable stock count adjustments';
