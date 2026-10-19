-- Preserve cash history; historical failures remain reserved until independently verified.
ALTER TABLE stl_payment
    ADD COLUMN refund_capacity_state VARCHAR(16) DEFAULT NULL,
    ADD COLUMN refund_query_state VARCHAR(32) DEFAULT NULL,
    ADD COLUMN refund_query_raw VARCHAR(64) DEFAULT NULL,
    ADD COLUMN refund_query_at BIGINT DEFAULT NULL,
    ADD COLUMN refund_terminal_proof LONGTEXT DEFAULT NULL,
    ADD COLUMN refund_recovery_proof LONGTEXT DEFAULT NULL,
    ADD KEY idx_stl_refund_capacity (origin_payment_no, refund_capacity_state),
    ADD KEY idx_stl_refund_order_capacity (order_no, direction, origin_payment_no);
