-- Preserve each actual merchant credit admission, including a verified COD -> platform collection revision.
CREATE TABLE ord_channel_funding_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
    updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
    history_no VARCHAR(32) NOT NULL, admission_no VARCHAR(32) NOT NULL,
    previous_collection VARCHAR(32) NULL, admitted_collection VARCHAR(32) NOT NULL,
    previous_snapshot LONGTEXT NULL, admitted_snapshot LONGTEXT NOT NULL,
    original_collection_hash VARCHAR(64) NOT NULL,
    UNIQUE KEY uk_ord_channel_funding_history(history_no),
    UNIQUE KEY uk_ord_channel_funding_collection(tenant_no,entity_no,store_no,admission_no,admitted_collection)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
