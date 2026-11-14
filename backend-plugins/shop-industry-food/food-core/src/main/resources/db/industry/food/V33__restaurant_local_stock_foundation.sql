-- Restaurant-owned stock. No rows are copied from retail or independent inventory.
CREATE TABLE fnb_stock_item (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 item_no VARCHAR(32) NOT NULL, item_kind VARCHAR(16) NOT NULL,
 sku_no VARCHAR(32) NULL, name VARCHAR(128) NOT NULL, base_unit VARCHAR(16) NOT NULL,
 tracking_mode VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL,
 UNIQUE KEY uk_fnb_stock_item_no(item_no),
 UNIQUE KEY uk_fnb_stock_item_sku(tenant_no,entity_no,sku_no),
 KEY ix_fnb_stock_item_entity(tenant_no,entity_no,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE fnb_stock_balance (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 balance_no VARCHAR(32) NOT NULL, item_no VARCHAR(32) NOT NULL,
 on_hand DECIMAL(24,6) NOT NULL DEFAULT 0, reserved_qty DECIMAL(24,6) NOT NULL DEFAULT 0,
 UNIQUE KEY uk_fnb_stock_balance_no(balance_no),
 UNIQUE KEY uk_fnb_stock_balance_item(tenant_no,entity_no,store_no,item_no),
 CHECK (on_hand >= 0 AND reserved_qty >= 0 AND reserved_qty <= on_hand)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE fnb_stock_reservation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 reservation_no VARCHAR(32) NOT NULL, source_system VARCHAR(32) NOT NULL,
 source_no VARCHAR(64) NOT NULL, source_version BIGINT NOT NULL,
 operation_key VARCHAR(128) NOT NULL, request_hash CHAR(64) NOT NULL, plan_hash CHAR(64) NOT NULL,
 lifecycle_version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, expires_at DATETIME(3) NULL,
 UNIQUE KEY uk_fnb_stock_reservation_no(reservation_no),
 UNIQUE KEY uk_fnb_stock_reservation_source(tenant_no,entity_no,store_no,source_system,source_no,source_version),
 UNIQUE KEY uk_fnb_stock_reservation_key(tenant_no,entity_no,store_no,operation_key),
 KEY ix_fnb_stock_reservation_recovery(status,updated_at,id),
 CHECK (source_version >= 0 AND lifecycle_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE fnb_stock_reservation_item (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 reservation_no VARCHAR(32) NOT NULL, leaf_ref VARCHAR(64) NOT NULL,
 source_component_ref VARCHAR(64) NOT NULL, item_no VARCHAR(32) NOT NULL,
 base_unit VARCHAR(16) NOT NULL, original_qty DECIMAL(24,6) NOT NULL,
 issued_qty DECIMAL(24,6) NOT NULL DEFAULT 0, released_qty DECIMAL(24,6) NOT NULL DEFAULT 0,
 phase VARCHAR(32) NOT NULL, definition_hash CHAR(64) NOT NULL,
 UNIQUE KEY uk_fnb_stock_reservation_leaf(tenant_no,entity_no,store_no,reservation_no,leaf_ref),
 KEY ix_fnb_stock_reservation_component(tenant_no,entity_no,store_no,reservation_no,source_component_ref),
 CHECK (original_qty > 0 AND issued_qty >= 0 AND released_qty >= 0
        AND issued_qty + released_qty <= original_qty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE fnb_stock_command (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 command_no VARCHAR(32) NOT NULL, action VARCHAR(32) NOT NULL, operation_key VARCHAR(128) NOT NULL,
 request_hash CHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, result_json LONGTEXT NULL,
 UNIQUE KEY uk_fnb_stock_command_no(command_no),
 UNIQUE KEY uk_fnb_stock_command_key(tenant_no,entity_no,store_no,action,operation_key),
 KEY ix_fnb_stock_command_recovery(status,updated_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE fnb_stock_movement (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 movement_no VARCHAR(32) NOT NULL, command_no VARCHAR(32) NOT NULL, line_ref VARCHAR(64) NOT NULL,
 item_no VARCHAR(32) NOT NULL, reservation_no VARCHAR(32) NULL, action VARCHAR(32) NOT NULL,
 quantity DECIMAL(24,6) NOT NULL, base_unit VARCHAR(16) NOT NULL,
 on_hand_before DECIMAL(24,6) NOT NULL, on_hand_after DECIMAL(24,6) NOT NULL,
 reserved_before DECIMAL(24,6) NOT NULL, reserved_after DECIMAL(24,6) NOT NULL,
 reason_code VARCHAR(32) NULL, reason_detail VARCHAR(256) NULL,
 UNIQUE KEY uk_fnb_stock_movement_no(movement_no),
 UNIQUE KEY uk_fnb_stock_movement_command(tenant_no,entity_no,store_no,command_no,line_ref),
 KEY ix_fnb_stock_movement_item(tenant_no,entity_no,store_no,item_no,created_at,id),
 CHECK (quantity > 0 AND on_hand_before >= 0 AND on_hand_after >= 0
        AND reserved_before >= 0 AND reserved_after >= 0
        AND reserved_before <= on_hand_before AND reserved_after <= on_hand_after)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
