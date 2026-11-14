-- MAIN is the single source-claim owner; these tables never duplicate physical stock balances.
CREATE TABLE prd_stock_writer (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(64) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(64) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 entity_no VARCHAR(32) NOT NULL, mode VARCHAR(16) NOT NULL,
 UNIQUE KEY uk_prd_stock_writer(tenant_no,entity_no)
 , CONSTRAINT ck_prd_stock_writer CHECK (mode IN ('LEGACY','REPLENISH') AND deleted=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE prd_stock_replenishment (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(64) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(64) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 original_no VARCHAR(32) NOT NULL, entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 target_owner VARCHAR(16) NOT NULL, source_system VARCHAR(32) NOT NULL, source_owner VARCHAR(32) NOT NULL,
 source_document VARCHAR(32) NOT NULL, source_line INT NOT NULL, binding_hash CHAR(64) NOT NULL,
 source_snapshot LONGTEXT NOT NULL, mapping_snapshot LONGTEXT NOT NULL, target_snapshot LONGTEXT NULL,
 admission_generation BIGINT NOT NULL DEFAULT 0, expected_quantity DECIMAL(24,6) NOT NULL,
 received_quantity DECIMAL(24,6) NOT NULL DEFAULT 0, difference_quantity DECIMAL(24,6) NOT NULL DEFAULT 0,
 returned_quantity DECIMAL(24,6) NOT NULL DEFAULT 0, return_accepted_quantity DECIMAL(24,6) NOT NULL DEFAULT 0,
 status VARCHAR(16) NOT NULL,
 UNIQUE KEY uk_prd_replenishment_no(original_no),
 UNIQUE KEY uk_prd_replenishment_source(tenant_no,source_system,source_owner,source_document,source_line),
 KEY ix_prd_replenishment_scope(tenant_no,entity_no,store_no,target_owner,id)
 , CONSTRAINT ck_prd_replenishment_identity CHECK (source_line>0 AND admission_generation>=0 AND target_owner IN ('FOOD_LOCAL','PLATFORM') AND deleted=0)
 , CONSTRAINT ck_prd_replenishment_snapshot CHECK (JSON_VALID(source_snapshot) AND JSON_VALID(mapping_snapshot) AND (target_snapshot IS NULL OR JSON_VALID(target_snapshot)))
 , CONSTRAINT ck_prd_replenishment_target CHECK (target_owner<>'PLATFORM' OR (target_snapshot IS NOT NULL AND expected_quantity=FLOOR(expected_quantity) AND expected_quantity<=2147483647))
 , CONSTRAINT ck_prd_replenishment_quantities CHECK (expected_quantity>0 AND received_quantity>=0 AND difference_quantity>=0 AND received_quantity+difference_quantity<=expected_quantity AND returned_quantity>=0 AND returned_quantity<=received_quantity AND return_accepted_quantity>=0 AND return_accepted_quantity<=returned_quantity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE prd_stock_replenishment_connection (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(64) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(64) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL, enabled INT NOT NULL DEFAULT 0,
 generation BIGINT NOT NULL DEFAULT 0, source_system VARCHAR(32) NULL, source_owner VARCHAR(32) NULL,
 posting_watermark BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_prd_replenishment_connection(tenant_no,entity_no,store_no)
 , CONSTRAINT ck_prd_replenishment_connection CHECK (enabled IN (0,1) AND generation>=0 AND posting_watermark>=0 AND (enabled=0 OR (source_system IS NOT NULL AND source_owner IS NOT NULL AND generation>0)) AND deleted=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE prd_stock_replenishment_command (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(64) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(64) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL, action VARCHAR(32) NOT NULL,
 operation_key VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL, request_hash CHAR(64) NOT NULL, result_json LONGTEXT NOT NULL,
 UNIQUE KEY uk_prd_replenishment_command(tenant_no,entity_no,store_no,action,operation_key)
 , CONSTRAINT ck_prd_replenishment_command CHECK (JSON_VALID(result_json) AND deleted=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
