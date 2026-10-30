-- Explicit hints are not withdrawal authority. Every use revalidates a matching original owner proof.
ALTER TABLE cim_source_notice ADD COLUMN notice_kind VARCHAR(32) NOT NULL DEFAULT 'CHANGE';
CREATE TABLE cim_source_withdrawal (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    version BIGINT NOT NULL DEFAULT 0,
    deleted INT NOT NULL DEFAULT 0,
    withdrawal_no VARCHAR(32) NOT NULL,
    entity_no VARCHAR(32) NOT NULL,
    store_no VARCHAR(32) NOT NULL,
    source_owner VARCHAR(32) NOT NULL,
    source_ref VARCHAR(128) NOT NULL,
    previous_revision VARCHAR(128) NOT NULL,
    withdrawal_revision VARCHAR(128) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    proof_snapshot LONGTEXT NOT NULL,
    notice_no VARCHAR(32) NULL,
    status VARCHAR(32) NOT NULL,
    UNIQUE KEY uk_cim_source_withdrawal_no(tenant_no,withdrawal_no),
    UNIQUE KEY uk_cim_source_withdrawal_original(tenant_no,entity_no,store_no,source_owner,source_ref,previous_revision,withdrawal_revision),
    KEY idx_cim_source_withdrawal_current(tenant_no,entity_no,store_no,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
