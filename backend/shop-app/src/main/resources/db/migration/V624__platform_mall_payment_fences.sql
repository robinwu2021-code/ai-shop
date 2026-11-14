-- The existing stl_payment remains the single cash ledger. These are send/fence/proof fields.
ALTER TABLE stl_payment
    ADD COLUMN platform_dispatch_at BIGINT DEFAULT NULL,
    ADD COLUMN platform_close_fence VARCHAR(128) DEFAULT NULL,
    ADD COLUMN platform_receipt_json MEDIUMTEXT DEFAULT NULL,
    ADD COLUMN platform_checkout_json MEDIUMTEXT DEFAULT NULL,
    ADD COLUMN platform_handoff_state VARCHAR(16) DEFAULT NULL,
    ADD INDEX idx_platform_handoff (pay_scene,platform_handoff_state,payment_no);
