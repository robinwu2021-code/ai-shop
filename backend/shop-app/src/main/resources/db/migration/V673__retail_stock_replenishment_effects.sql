-- Quantity effects stay in the original PLATFORM counters; all evidence commits with their command.
ALTER TABLE prd_stock_replenishment ADD COLUMN close_reason VARCHAR(256) NULL;
ALTER TABLE prd_stock_replenishment ADD CONSTRAINT ck_prd_repl_integer_totals CHECK (target_owner<>'PLATFORM' OR (received_quantity=FLOOR(received_quantity) AND difference_quantity=FLOOR(difference_quantity) AND returned_quantity=FLOOR(returned_quantity) AND return_accepted_quantity=FLOOR(return_accepted_quantity)));
CREATE TABLE prd_stock_replenishment_movement (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(64) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(64) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 movement_no VARCHAR(32) NOT NULL, original_no VARCHAR(32) NULL,
 command_action VARCHAR(32) NOT NULL, operation_key VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
 kind VARCHAR(24) NOT NULL, item_no VARCHAR(32) NOT NULL, base_unit VARCHAR(16) NOT NULL,
 target_identity CHAR(64) NOT NULL, target_snapshot LONGTEXT NOT NULL,
 delta INT NOT NULL, on_hand_before INT NOT NULL, on_hand_after INT NOT NULL,
 reserved_before INT NOT NULL, reserved_after INT NOT NULL, reason VARCHAR(256) NULL,
 UNIQUE KEY uk_prd_repl_movement(movement_no),
 UNIQUE KEY uk_prd_repl_effect(tenant_no,entity_no,store_no,command_action,operation_key),
 KEY ix_prd_repl_movement_original(tenant_no,entity_no,store_no,original_no,kind)
 , CONSTRAINT ck_prd_repl_movement_qty CHECK (delta<>0 AND delta>=-2147483647 AND on_hand_before>=0 AND on_hand_after>=0 AND reserved_before>=0 AND reserved_after=reserved_before AND reserved_before<=on_hand_before AND reserved_after<=on_hand_after AND on_hand_after=on_hand_before+delta)
 , CONSTRAINT ck_prd_repl_movement_kind CHECK ((kind IN ('RECEIVE','ADJUST_IN') AND delta>0 OR kind IN ('RETURN','ADJUST_OUT') AND delta<0) AND deleted=0)
 , CONSTRAINT ck_prd_repl_movement_target CHECK (JSON_VALID(target_snapshot))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE prd_stock_replenishment_manual (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(64) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(64) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 original_no VARCHAR(32) NOT NULL, movement_no VARCHAR(32) NOT NULL, quantity INT NOT NULL,
 UNIQUE KEY uk_prd_repl_manual_movement(tenant_no,movement_no),
 KEY ix_prd_repl_manual_original(tenant_no,entity_no,store_no,original_no)
 , CONSTRAINT ck_prd_repl_manual CHECK (quantity>0 AND deleted=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE prd_stock_replenishment_return (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL, created_by VARCHAR(64) NOT NULL,
 updated_at DATETIME(3) NOT NULL, updated_by VARCHAR(64) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
 original_no VARCHAR(32) NOT NULL, source_system VARCHAR(32) NOT NULL, source_owner VARCHAR(32) NOT NULL,
 source_document VARCHAR(32) NOT NULL, source_line INT NOT NULL, accepted_quantity INT NOT NULL,
 proof_hash CHAR(64) NOT NULL, source_snapshot LONGTEXT NOT NULL,
 UNIQUE KEY uk_prd_repl_return_source(tenant_no,source_system,source_owner,source_document,source_line),
 KEY ix_prd_repl_return_original(tenant_no,entity_no,store_no,original_no)
 , CONSTRAINT ck_prd_repl_return CHECK (source_line>0 AND accepted_quantity>0 AND deleted=0 AND JSON_VALID(source_snapshot))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
