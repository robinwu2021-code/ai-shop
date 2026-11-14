ALTER TABLE inv_reservation
    ADD COLUMN lifecycle_version INT NOT NULL DEFAULT 1 COMMENT '1=payment deduct, 2=handoff deduct';

ALTER TABLE inv_reservation_line
    ADD COLUMN fulfillment_ref VARCHAR(64) DEFAULT NULL COMMENT 'Merchant sub-order owning this reserved line',
    ADD COLUMN issued_qty INT NOT NULL DEFAULT 0,
    ADD COLUMN released_qty INT NOT NULL DEFAULT 0,
    ADD COLUMN outbound_no VARCHAR(64) DEFAULT NULL,
    ADD COLUMN issued_at DATETIME DEFAULT NULL,
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    ADD COLUMN updated_by VARCHAR(64) DEFAULT NULL,
    ADD KEY idx_inv_reservation_fulfillment (owner_id, fulfillment_ref);
