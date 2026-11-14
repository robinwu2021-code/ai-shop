-- OPS zero-value cuts retain immutable original source economics and explicit operation proof.
ALTER TABLE mch_subscription_period
    ADD COLUMN revoked_by_no VARCHAR(64) NULL,
    ADD COLUMN revocation_snapshot JSON NULL;
