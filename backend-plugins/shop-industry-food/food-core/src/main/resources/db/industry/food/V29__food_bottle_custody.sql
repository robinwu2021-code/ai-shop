-- Extend the original V1 customer-property ledger; do not recreate it or infer old deposit totals.
ALTER TABLE fnb_bottle_custody ADD COLUMN deposited_qty DECIMAL(18,6) NULL;
CREATE INDEX idx_fnb_bottle_owner ON fnb_bottle_custody(tenant_no,entity_no,store_no,owner_ref,id);
CREATE TABLE fnb_bottle_movement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
    updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
    movement_no VARCHAR(32) NOT NULL, custody_no VARCHAR(32) NOT NULL, action VARCHAR(24) NOT NULL,
    quantity DECIMAL(20,6) NOT NULL, remaining_qty DECIMAL(20,6) NOT NULL, unit VARCHAR(16) NOT NULL,
    actor_no VARCHAR(32) NOT NULL, recipient_no VARCHAR(32) NOT NULL, reason VARCHAR(255) NOT NULL,
    original_receipt_ref VARCHAR(128) NULL, key_hash VARCHAR(64) NOT NULL, request_hash VARCHAR(64) NOT NULL,
    result_snapshot LONGTEXT NOT NULL,
    UNIQUE KEY uk_fnb_bottle_movement_no(movement_no),
    UNIQUE KEY uk_fnb_bottle_movement_key(tenant_no,entity_no,store_no,key_hash),
    KEY idx_fnb_bottle_movement_owner(tenant_no,entity_no,store_no,custody_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
