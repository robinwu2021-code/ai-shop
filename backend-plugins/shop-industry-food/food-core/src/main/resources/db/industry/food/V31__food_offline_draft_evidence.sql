-- Draft evidence only; no offline checkout, payment, stock promise, kitchen or on-device database.
CREATE TABLE fnb_offline_signer (
id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL, updated_at DATETIME(3) NOT NULL,
updated_by VARCHAR(32) NOT NULL, version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
key_no VARCHAR(32) NOT NULL, device_no VARCHAR(32) NOT NULL, device_generation BIGINT NOT NULL,
public_key VARCHAR(128) NOT NULL, public_key_hash CHAR(64) NOT NULL,
UNIQUE KEY uk_offline_signer_no(key_no), UNIQUE KEY uk_offline_signer_device(entity_no,store_no,device_no,device_generation)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE fnb_offline_challenge (
id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL, updated_at DATETIME(3) NOT NULL,
updated_by VARCHAR(32) NOT NULL, version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
challenge_no VARCHAR(32) NOT NULL, device_no VARCHAR(32) NOT NULL, device_generation BIGINT NOT NULL,
public_key VARCHAR(128) NOT NULL, challenge_json LONGTEXT NOT NULL, expires_at DATETIME(3) NOT NULL,
key_no VARCHAR(32) NULL, UNIQUE KEY uk_offline_challenge_no(challenge_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE fnb_offline_writer (
id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL, updated_at DATETIME(3) NOT NULL,
updated_by VARCHAR(32) NOT NULL, version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
device_no VARCHAR(32) NULL, device_generation BIGINT NULL, key_no VARCHAR(32) NULL, lease_no VARCHAR(32) NULL,
fence BIGINT NOT NULL DEFAULT 0, expires_at DATETIME(3) NULL, accepted_through BIGINT NOT NULL DEFAULT 0,
UNIQUE KEY uk_offline_writer_store(entity_no,store_no), UNIQUE KEY uk_offline_writer_lease(lease_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE fnb_offline_draft (
id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL, updated_at DATETIME(3) NOT NULL,
updated_by VARCHAR(32) NOT NULL, version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
draft_no VARCHAR(32) NOT NULL, device_no VARCHAR(32) NOT NULL, device_generation BIGINT NOT NULL,
key_no VARCHAR(32) NOT NULL, lease_no VARCHAR(32) NOT NULL, fence BIGINT NOT NULL, sequence_no BIGINT NOT NULL,
payload_json LONGTEXT NOT NULL, payload_hash CHAR(64) NOT NULL, signature_value VARCHAR(128) NOT NULL,
signature_hash CHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, session_no VARCHAR(32) NULL, cart_no VARCHAR(32) NULL,
applied_lines INT NOT NULL DEFAULT 0, reason VARCHAR(64) NULL, receipt_no VARCHAR(32) NOT NULL,
UNIQUE KEY uk_offline_draft_no(draft_no), UNIQUE KEY uk_offline_draft_sequence(entity_no,store_no,lease_no,sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE fnb_offline_line (
id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL, updated_at DATETIME(3) NOT NULL,
updated_by VARCHAR(32) NOT NULL, version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
draft_no VARCHAR(32) NOT NULL, line_index INT NOT NULL, source_line_ref VARCHAR(32) NOT NULL,
cart_version BIGINT NOT NULL, owner_key VARCHAR(128) NOT NULL,
UNIQUE KEY uk_offline_line_progress(draft_no,line_index), UNIQUE KEY uk_offline_line_source(draft_no,source_line_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE fnb_offline_audit (
id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN', entity_no VARCHAR(32) NOT NULL, store_no VARCHAR(32) NOT NULL,
created_at DATETIME(3) NOT NULL, created_by VARCHAR(32) NOT NULL, updated_at DATETIME(3) NOT NULL,
updated_by VARCHAR(32) NOT NULL, version BIGINT NOT NULL DEFAULT 0, deleted INT NOT NULL DEFAULT 0,
receipt_no VARCHAR(32) NOT NULL, device_no VARCHAR(32) NOT NULL, operation_kind VARCHAR(24) NOT NULL,
request_key VARCHAR(128) NOT NULL, request_hash CHAR(64) NOT NULL, signature_hash CHAR(64) NULL,
status VARCHAR(32) NOT NULL, reason VARCHAR(64) NULL, original_payload LONGTEXT NOT NULL, result_json LONGTEXT NOT NULL,
UNIQUE KEY uk_offline_audit_receipt(receipt_no),
UNIQUE KEY uk_offline_audit_command(entity_no,store_no,device_no,operation_kind,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

