-- Stable source request identity survives generic idempotency-cache expiry.
-- Historical rows stay nullable; every new service-owned request supplies the complete tuple.
ALTER TABLE fnb_return
    ADD COLUMN batch_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN buyer_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN request_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN request_hash CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN request_snapshot JSON NULL,
    ADD UNIQUE KEY uk_food_return_request (entity_no, store_no, request_key);

ALTER TABLE fnb_settlement
    ADD COLUMN request_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN request_hash CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN request_snapshot JSON NULL,
    ADD COLUMN buyer_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD UNIQUE KEY uk_food_settlement_request (entity_no, store_no, request_key);

-- MAIN units also carry a canonical pricing-component hash; preserve it through order and refund mapping.
ALTER TABLE fnb_core_line_allocation
    ADD COLUMN source_component_ref CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;


-- Source-side projection recovery is scheduled fairly; claims never create another financial intent.
ALTER TABLE fnb_order_batch ADD COLUMN next_recovery_at DATETIME(3) NULL;
ALTER TABLE fnb_settlement ADD COLUMN next_recovery_at DATETIME(3) NULL;
ALTER TABLE fnb_return ADD COLUMN next_recovery_at DATETIME(3) NULL;
