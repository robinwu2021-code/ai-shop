-- Industry endpoints contain a namespaced SHA-256 (15 + 64 characters).
-- Preserve the existing identity and idempotency key; widening is backward compatible.
ALTER TABLE ord_create_intent MODIFY COLUMN endpoint VARCHAR(128) NOT NULL,
    MODIFY COLUMN stock_authority VARCHAR(32) NOT NULL;
