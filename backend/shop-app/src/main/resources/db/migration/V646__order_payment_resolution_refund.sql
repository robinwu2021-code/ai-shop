-- Original channel coordinates and cash-result evidence for the manually initiated late-payment return.
ALTER TABLE ord_payment_resolution
    ADD COLUMN origin_out_trade_no VARCHAR(64) NULL,
    ADD COLUMN refund_error_code VARCHAR(128) NULL;
