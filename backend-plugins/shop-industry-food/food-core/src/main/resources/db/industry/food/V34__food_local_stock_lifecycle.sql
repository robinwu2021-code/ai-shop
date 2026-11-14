-- Preserve unknown historical policy until explicitly configured; never invent stock facts.
ALTER TABLE fnb_stock_balance ADD COLUMN tracking_mode VARCHAR(16) NULL;
ALTER TABLE fnb_stock_balance ADD COLUMN status VARCHAR(16) NULL;
ALTER TABLE fnb_stock_reservation_item ADD COLUMN definition_snapshot LONGTEXT NULL;
ALTER TABLE fnb_stock_reservation_item ADD COLUMN returned_qty DECIMAL(24,6) NOT NULL DEFAULT 0;
ALTER TABLE fnb_stock_reservation_item ADD CONSTRAINT ck_fnb_stock_returned CHECK (returned_qty >= 0 AND returned_qty <= issued_qty);
ALTER TABLE fnb_stock_movement ADD COLUMN original_movement_no VARCHAR(32) NULL;
CREATE INDEX ix_fnb_stock_movement_original ON fnb_stock_movement(tenant_no,entity_no,store_no,original_movement_no);
ALTER TABLE fnb_stock_command ADD COLUMN request_snapshot LONGTEXT NULL;
ALTER TABLE fnb_stock_reservation ADD COLUMN source_plan_hash CHAR(64) NULL;
ALTER TABLE fnb_stock_reservation ADD COLUMN protocol_version INT NULL;
ALTER TABLE fnb_stock_reservation_item ADD COLUMN last_issue_movement_no VARCHAR(32) NULL;
ALTER TABLE fnb_stock_reservation_item ADD COLUMN last_issue_command_no VARCHAR(32) NULL;
ALTER TABLE fnb_stock_item ADD COLUMN weighed TINYINT NULL;
