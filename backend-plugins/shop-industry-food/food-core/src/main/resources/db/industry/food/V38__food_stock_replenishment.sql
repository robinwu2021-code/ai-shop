-- Immutable source identities intentionally exclude connection generations and request keys.
CREATE TABLE fnb_stock_replenishment (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 replenishment_no VARCHAR(32) NOT NULL,
 source_system VARCHAR(32) NOT NULL, source_owner VARCHAR(32) NOT NULL,
 source_document VARCHAR(32) NOT NULL, source_line INT NOT NULL,
 source_item VARCHAR(32) NOT NULL, source_unit VARCHAR(16) NOT NULL,
 source_quantity DECIMAL(24,6) NOT NULL,
 item_no VARCHAR(32) NOT NULL, base_unit VARCHAR(16) NOT NULL, factor DECIMAL(24,6) NOT NULL,
 expected_quantity DECIMAL(24,6) NOT NULL, received_quantity DECIMAL(24,6) NOT NULL DEFAULT 0,
 difference_quantity DECIMAL(24,6) NOT NULL DEFAULT 0, returned_quantity DECIMAL(24,6) NOT NULL DEFAULT 0,
 return_accepted_quantity DECIMAL(24,6) NOT NULL DEFAULT 0,
 status VARCHAR(16) NOT NULL, close_reason VARCHAR(256) NULL,
 UNIQUE KEY uk_fnb_replenishment_no(replenishment_no),
 UNIQUE KEY uk_fnb_replenishment_source(tenant_no,source_system,source_owner,source_document,source_line),
 KEY ix_fnb_replenishment_store(tenant_no,entity_no,store_no,status,id),
 CHECK (source_line>0 AND source_quantity>0 AND factor>0 AND expected_quantity>0),
 CHECK (received_quantity>=0 AND difference_quantity>=0 AND received_quantity+difference_quantity<=expected_quantity
    AND returned_quantity>=0 AND returned_quantity<=received_quantity
    AND return_accepted_quantity>=0 AND return_accepted_quantity<=returned_quantity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE fnb_stock_replenishment_return (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 replenishment_no VARCHAR(32) NOT NULL,
 source_system VARCHAR(32) NOT NULL, source_owner VARCHAR(32) NOT NULL,
 source_document VARCHAR(32) NOT NULL, source_line INT NOT NULL,
 accepted_quantity DECIMAL(24,6) NOT NULL,
 UNIQUE KEY uk_fnb_replenishment_return_source(tenant_no,source_system,source_owner,source_document,source_line),
 KEY ix_fnb_replenishment_return_ref(tenant_no,entity_no,store_no,replenishment_no),
 CHECK (source_line>0 AND accepted_quantity>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
