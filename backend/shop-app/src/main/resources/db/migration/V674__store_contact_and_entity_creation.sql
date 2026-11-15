-- Contact is a separate store settings section; original migrations remain frozen.
ALTER TABLE mch_store
 ADD COLUMN contact_name VARCHAR(64) NULL,
 ADD COLUMN contact_phone VARCHAR(32) NULL;

-- Permanent entity-only intent receipt, committed with entity/owner/FREE initialization.
CREATE TABLE mch_entity_creation_request (
 tenant_no VARCHAR(32) NOT NULL,
 user_no VARCHAR(64) NOT NULL,
 request_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 intent_hash CHAR(64) NOT NULL,
 entity_no VARCHAR(64) NULL,
 created_at BIGINT NOT NULL,
 PRIMARY KEY (tenant_no, user_no, request_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
