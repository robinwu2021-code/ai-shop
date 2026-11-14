-- Optional outbound projection; OFF until explicitly enabled. No inventory authority transfer.
CREATE TABLE fnb_stock_sync_config (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 target_system VARCHAR(32) NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'OFF',
 generation BIGINT NOT NULL DEFAULT 0, baseline_cursor BIGINT NOT NULL DEFAULT 0, baseline_end_id BIGINT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(3) NULL, last_error_code VARCHAR(64) NULL,
 enabled_at DATETIME(3) NULL, disabled_at DATETIME(3) NULL,
 UNIQUE KEY uk_fnb_stock_sync_config(tenant_no,entity_no,store_no,target_system),
 KEY ix_fnb_stock_sync_config_recovery(status,next_attempt_at,id),
 CHECK (generation >= 0 AND baseline_cursor >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE fnb_stock_sync_mapping (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 item_no VARCHAR(32) NOT NULL, target_system VARCHAR(32) NOT NULL,
 target_owner_ref VARCHAR(64) NULL, target_location_ref VARCHAR(64) NULL, target_item_ref VARCHAR(64) NULL,
 source_unit VARCHAR(16) NOT NULL, target_unit VARCHAR(16) NOT NULL,
 mapping_revision BIGINT NOT NULL, status VARCHAR(32) NOT NULL,
 UNIQUE KEY uk_fnb_stock_sync_mapping(tenant_no,entity_no,store_no,item_no,target_system),
 CHECK (mapping_revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE fnb_stock_sync_item_state (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 item_no VARCHAR(32) NOT NULL, source_version BIGINT NOT NULL,
 snapshot_hash CHAR(64) NOT NULL,
 UNIQUE KEY uk_fnb_stock_sync_item_state(tenant_no,entity_no,store_no,item_no),
 CHECK (source_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE fnb_stock_sync_delivery (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 sync_no VARCHAR(32) NOT NULL, event_no VARCHAR(64) NOT NULL, item_no VARCHAR(32) NOT NULL,
 generation BIGINT NOT NULL, source_version BIGINT NOT NULL, payload_hash CHAR(64) NOT NULL,
 operation_key VARCHAR(128) NOT NULL, payload_snapshot LONGTEXT NOT NULL, status VARCHAR(32) NOT NULL,
 attempts INT NOT NULL DEFAULT 0, next_attempt_at DATETIME(3) NULL,
 last_error_code VARCHAR(64) NULL, ack_ref VARCHAR(64) NULL,
 UNIQUE KEY uk_fnb_stock_sync_delivery_no(sync_no), UNIQUE KEY uk_fnb_stock_sync_event(event_no),
 UNIQUE KEY uk_fnb_stock_sync_delivery_source(tenant_no,entity_no,store_no,item_no,generation,source_version),
 KEY ix_fnb_stock_sync_delivery_recovery(status,next_attempt_at,id),
 CHECK (generation >= 0 AND source_version >= 0 AND attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE fnb_stock_sync_reconciliation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 reconciliation_no VARCHAR(32) NOT NULL, generation BIGINT NOT NULL,
 operation_key VARCHAR(128) NOT NULL, request_hash CHAR(64) NOT NULL, status VARCHAR(32) NOT NULL,
 cursor_id BIGINT NOT NULL DEFAULT 0, end_id BIGINT NOT NULL DEFAULT 0, request_snapshot LONGTEXT NOT NULL, checked_count BIGINT NOT NULL DEFAULT 0,
 difference_count BIGINT NOT NULL DEFAULT 0, started_at DATETIME(3) NULL, finished_at DATETIME(3) NULL,
 last_error_code VARCHAR(64) NULL,
 UNIQUE KEY uk_fnb_stock_sync_reconciliation_no(reconciliation_no),
 UNIQUE KEY uk_fnb_stock_sync_reconciliation_key(tenant_no,entity_no,store_no,operation_key),
 KEY ix_fnb_stock_sync_reconciliation_recovery(status,updated_at,id),
 CHECK (generation >= 0 AND cursor_id >= 0 AND checked_count >= 0 AND difference_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE fnb_stock_sync_difference (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 difference_no VARCHAR(32) NOT NULL, reconciliation_no VARCHAR(32) NOT NULL,
 item_no VARCHAR(32) NOT NULL, generation BIGINT NOT NULL,
 source_version BIGINT NULL, target_version BIGINT NULL, difference_type VARCHAR(32) NOT NULL,
 source_snapshot LONGTEXT NULL, target_snapshot LONGTEXT NULL, resolution_status VARCHAR(32) NOT NULL,
 UNIQUE KEY uk_fnb_stock_sync_difference_no(difference_no),
 UNIQUE KEY uk_fnb_stock_sync_difference_item(tenant_no,entity_no,store_no,reconciliation_no,item_no,difference_type),
 KEY ix_fnb_stock_sync_difference_page(tenant_no,entity_no,store_no,reconciliation_no,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
