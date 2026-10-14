-- Trusted refund query evidence is independent of the original acceptance receipt.
ALTER TABLE stl_payment ADD COLUMN refund_verified_minor BIGINT DEFAULT NULL COMMENT 'Actual refund minor verified by channel query';
ALTER TABLE stl_payment ADD COLUMN refund_verified_provider_no VARCHAR(128) DEFAULT NULL COMMENT 'Verified query refund receipt';
ALTER TABLE stl_payment ADD COLUMN refund_verified_at BIGINT DEFAULT NULL COMMENT 'Verification epoch millis';
CREATE UNIQUE INDEX uk_stl_refund_verified_provider ON stl_payment(pay_channel,refund_verified_provider_no);
