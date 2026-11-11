-- Existing accepted originals remain legacy wind-down authority; no balance import or reset.
ALTER TABLE fnb_stock_replenishment ADD COLUMN admission_generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE fnb_stock_replenishment ADD COLUMN admission_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE fnb_stock_replenishment ADD COLUMN source_posting_sequence BIGINT NULL;
CREATE TABLE fnb_stock_replenishment_connection (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 status VARCHAR(16) NOT NULL, generation BIGINT NOT NULL DEFAULT 0,
 source_system VARCHAR(32) NULL, source_owner VARCHAR(32) NULL, posting_watermark BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_fnb_replenishment_connection(tenant_no,entity_no,store_no),
 CHECK (generation>=0 AND posting_watermark>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE fnb_stock_replenishment_manual (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 replenishment_no VARCHAR(32) NOT NULL, movement_no VARCHAR(32) NOT NULL, quantity DECIMAL(24,6) NOT NULL,
 UNIQUE KEY uk_fnb_replenishment_manual_movement(tenant_no,movement_no),
 KEY ix_fnb_replenishment_manual_ref(tenant_no,entity_no,store_no,replenishment_no),
 CHECK (quantity>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
