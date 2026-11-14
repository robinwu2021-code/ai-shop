-- Request receipts are committed atomically with the existing opening transaction.
CREATE TABLE mch_store_opening_request (
 tenant_no VARCHAR(32) NOT NULL,
 actor_key VARCHAR(128) NOT NULL,
 request_no VARCHAR(64) NOT NULL,
 intent_hash CHAR(64) NOT NULL,
 entity_no VARCHAR(64) NULL,
 store_no VARCHAR(64) NULL,
 created_at BIGINT NOT NULL,
 PRIMARY KEY (tenant_no, actor_key, request_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
