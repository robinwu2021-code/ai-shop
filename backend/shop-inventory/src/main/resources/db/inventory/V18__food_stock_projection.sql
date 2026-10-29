-- Restaurant-source mirrors never modify native inv_stock_balance or create posting documents.
CREATE TABLE inv_food_stock_projection (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 source_system VARCHAR(32) NOT NULL, source_item_no VARCHAR(32) NOT NULL,
 target_owner_id VARCHAR(64) NULL, target_location_id VARCHAR(64) NULL, target_item_id VARCHAR(64) NULL,
 base_unit VARCHAR(16) NOT NULL, tracking_mode VARCHAR(16) NOT NULL, status VARCHAR(32) NOT NULL,
 on_hand DECIMAL(24,6) NULL, reserved_qty DECIMAL(24,6) NULL,
 source_version BIGINT NOT NULL, generation BIGINT NOT NULL, payload_hash CHAR(64) NOT NULL,
 source_updated_at DATETIME(3) NOT NULL, received_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_inv_food_stock_projection(tenant_no,entity_no,store_no,source_system,source_item_no),
 CHECK (source_version >= 0 AND generation >= 0),
 CHECK ((tracking_mode='UNTRACKED' AND on_hand IS NULL AND reserved_qty IS NULL)
 OR (tracking_mode='TRACKED' AND on_hand IS NOT NULL AND reserved_qty IS NOT NULL
 AND on_hand >= 0 AND reserved_qty >= 0 AND reserved_qty <= on_hand))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE inv_food_stock_sync_receipt (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 source_system VARCHAR(32) NOT NULL, event_no VARCHAR(64) NOT NULL,
 generation BIGINT NOT NULL, payload_hash CHAR(64) NOT NULL, receipt_no VARCHAR(32) NOT NULL,
 result_json LONGTEXT NOT NULL, applied_version BIGINT NULL, received_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_inv_food_stock_receipt_no(receipt_no),
 UNIQUE KEY uk_inv_food_stock_receipt_event(tenant_no,entity_no,store_no,source_system,event_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE inv_food_stock_sync_session (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 source_system VARCHAR(32) NOT NULL, generation BIGINT NOT NULL, status VARCHAR(32) NOT NULL,
 activated_at DATETIME(3) NULL, disabled_at DATETIME(3) NULL,
 UNIQUE KEY uk_inv_food_stock_session(tenant_no,entity_no,store_no,source_system),
 CHECK (generation >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
