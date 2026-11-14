-- Durable source request identity lives on the original case, including after idempotency cache expiry.
ALTER TABLE ord_after_sale ADD COLUMN source_system VARCHAR(32) NULL;
ALTER TABLE ord_after_sale ADD COLUMN source_request_key CHAR(64) NULL;
ALTER TABLE ord_after_sale ADD COLUMN source_request_hash CHAR(64) NULL;
ALTER TABLE ord_after_sale ADD UNIQUE KEY uk_after_sale_source_request (source_system, source_request_key);
ALTER TABLE ord_after_sale ADD CONSTRAINT ck_after_sale_source_request CHECK (
    (source_system IS NULL AND source_request_key IS NULL AND source_request_hash IS NULL)
    OR (source_system IS NOT NULL AND source_request_key IS NOT NULL AND source_request_hash IS NOT NULL)
);
