-- Immutable partial-return intent plus independently recoverable physical stock disposition.
CREATE TABLE fnb_return_stock_disposition (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',entity_no VARCHAR(32) NOT NULL,store_no VARCHAR(32) NOT NULL,
 created_at DATETIME(3) NOT NULL,created_by VARCHAR(32) NOT NULL,updated_at DATETIME(3) NOT NULL,updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,deleted INT NOT NULL DEFAULT 0,
 operation_no VARCHAR(32) NOT NULL,return_no VARCHAR(32) NOT NULL,return_item_no VARCHAR(32) NOT NULL,
 production_event_no VARCHAR(32) NOT NULL,dish_qty DECIMAL(24,6) NOT NULL,disposition VARCHAR(16) NOT NULL,
 operation_key VARCHAR(128) NOT NULL,request_hash CHAR(64) NOT NULL,request_snapshot LONGTEXT NOT NULL,
 native_snapshot LONGTEXT NOT NULL,status VARCHAR(16) NOT NULL,stock_command_no VARCHAR(32) NULL,
 result_snapshot LONGTEXT NULL,last_error_code VARCHAR(64) NULL,
 UNIQUE KEY uk_fnb_return_stock_no(operation_no),
 UNIQUE KEY uk_fnb_return_stock_key(tenant_no,entity_no,store_no,operation_key),
 KEY ix_fnb_return_stock_claim(tenant_no,entity_no,store_no,return_item_no),
 KEY ix_fnb_return_stock_recovery(status,updated_at,id),
 CHECK(dish_qty>0),CHECK(disposition IN ('RESALE','LOSS'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
