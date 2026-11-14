-- Source-owned payment profile revisions and exact external submission originals.
-- Historical entity timestamps, source links and payment facts are never rewritten.
-- The sole source writer binds the complete request under the entity root lock;
-- database constraints retain command uniqueness, valid JSON and immutable originals.
CREATE TABLE mch_pay_service_application (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_caller VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_scope VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    request_no VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    profile_revision BIGINT NOT NULL,
    source_event_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    actor_realm VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    actor_no VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    request_json LONGTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    created_by VARCHAR(64) NULL,
    updated_by VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mpsa_request (tenant_no,source_caller,source_scope,request_no),
    UNIQUE KEY uk_mpsa_revision (tenant_no,entity_no,source_caller,source_scope,profile_revision),
    UNIQUE KEY uk_mpsa_event (tenant_no,source_caller,source_scope,source_event_id),
    CONSTRAINT ck_mpsa_base CHECK (tenant_no='MAIN' AND profile_revision>0 AND version=0 AND deleted=0),
    CONSTRAINT ck_mpsa_actor CHECK (actor_realm IN ('OPERATOR','MERCHANT') AND CHAR_LENGTH(TRIM(actor_no))>0),
    CONSTRAINT ck_mpsa_hash CHECK (request_hash REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_mpsa_json CHECK (JSON_VALID(request_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

DELIMITER $$
CREATE TRIGGER trg_mpsa_no_update BEFORE UPDATE ON mch_pay_service_application
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Payment service submission original is immutable';
END$$
CREATE TRIGGER trg_mpsa_no_delete BEFORE DELETE ON mch_pay_service_application
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Payment service submission original is permanent';
END$$
DELIMITER ;
