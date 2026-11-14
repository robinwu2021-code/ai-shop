-- Legacy preparation receipts remain readable but cannot authorize a new platform acceptance.
ALTER TABLE cim_order_link ADD COLUMN preparation_valid_until DATETIME(3) NULL;
ALTER TABLE cim_order_revision ADD COLUMN source_operation_no VARCHAR(32) NULL;
