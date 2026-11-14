ALTER TABLE mch_entity ADD COLUMN ownership_change_no VARCHAR(64) NULL;
ALTER TABLE mch_store ADD COLUMN ownership_change_no VARCHAR(64) NULL;
-- Preserve the millisecond sync revision without writing a future whole second.
ALTER TABLE mch_store MODIFY COLUMN updated_at DATETIME(3) NOT NULL;

CREATE TABLE mch_store_entity_claim (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(64) NOT NULL DEFAULT 'MAIN',
 claim_no VARCHAR(64) NOT NULL,
 owner_user_no VARCHAR(64) NOT NULL,
 request_no VARCHAR(64) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 source_scope VARCHAR(128) NOT NULL,
 source_entity_no VARCHAR(64) NOT NULL,
 target_entity_no VARCHAR(64) NOT NULL,
 store_no VARCHAR(64) NOT NULL,
 store_ref VARCHAR(64) NOT NULL,
 expected_store_version BIGINT NOT NULL,
 expected_pay_revision BIGINT NOT NULL,
 source_retire_planned TINYINT NOT NULL,
 state VARCHAR(24) NOT NULL,
 remote_ref VARCHAR(64) NULL,
 revoked_staff INT NOT NULL DEFAULT 0,
 placeholder_retired TINYINT NOT NULL DEFAULT 0,
 result_json MEDIUMTEXT NULL,
 last_error VARCHAR(64) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 active_store_no VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN state IN ('COMPLETED','ABORTED') THEN NULL ELSE store_no END) STORED,
 active_source_no VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN state IN ('COMPLETED','ABORTED') THEN NULL ELSE source_entity_no END) STORED,
 UNIQUE KEY uk_store_claim_no(tenant_no,claim_no),
 UNIQUE KEY uk_store_claim_request(tenant_no,owner_user_no,request_no),
 UNIQUE KEY uk_store_claim_active_store(tenant_no,active_store_no),
 UNIQUE KEY uk_store_claim_active_source(tenant_no,active_source_no),
 KEY idx_store_claim_recovery(state,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
