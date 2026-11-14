ALTER TABLE ord_pricing_template
    ADD COLUMN seed_version VARCHAR(64) DEFAULT NULL,
    ADD COLUMN seed_hash CHAR(64) DEFAULT NULL,
    ADD COLUMN seed_snapshot LONGTEXT DEFAULT NULL,
    ADD KEY idx_ord_pricing_seed (tenant_no,seed_version),
    ADD CONSTRAINT ck_ord_pricing_seed_shape CHECK (
        (seed_version IS NULL AND seed_hash IS NULL AND seed_snapshot IS NULL)
        OR (seed_version IS NOT NULL AND seed_hash IS NOT NULL AND seed_snapshot IS NOT NULL)),
    ADD CONSTRAINT ck_ord_pricing_seed_json CHECK (seed_snapshot IS NULL OR JSON_VALID(seed_snapshot));
