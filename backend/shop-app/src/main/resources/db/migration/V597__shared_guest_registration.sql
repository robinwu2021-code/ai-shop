-- Member owns shared guest identity; no industry buyer/account/order is created.
CREATE TABLE mbr_guest_registration (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Internal physical key',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN' COMMENT 'Tenant',
    entity_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'Owning entity',
    store_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'Owning store',
    guest_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'Public guest identifier',
    actor_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'Original registering actor',
    actor_realm VARCHAR(16) NOT NULL COMMENT 'Original identity realm',
    actor_kind VARCHAR(8) NOT NULL COMMENT 'Original subject kind',
    key_hash CHAR(64) NOT NULL COMMENT 'Original key SHA256',
    request_hash CHAR(64) NOT NULL COMMENT 'Original request SHA256',
    contact_cipher TEXT NULL COMMENT 'AES GCM contact, never plaintext',
    display_name VARCHAR(80) NULL COMMENT 'Guest supplied display name',
    claim_token_hash CHAR(64) NOT NULL COMMENT 'Secret comparison hash',
    claim_token_cipher TEXT NOT NULL COMMENT 'AES GCM original replay secret',
    state VARCHAR(16) NOT NULL COMMENT 'REGISTERED or CLAIMED',
    claimed_user_no VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT 'Real active account at claim',
    claimed_at DATETIME(3) NULL COMMENT 'UTC unique claim time',
    claim_key_hash CHAR(64) NULL COMMENT 'Claim idempotency key SHA256',
    claim_request_hash CHAR(64) NULL COMMENT 'Claim request SHA256',
    claim_actor_no VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT 'Actual claiming operator',
    claim_actor_realm VARCHAR(16) NULL COMMENT 'Claiming identity realm',
    claim_actor_kind VARCHAR(8) NULL COMMENT 'Claiming subject kind',
    created_at DATETIME(3) NOT NULL COMMENT 'Created UTC',
    created_by VARCHAR(64) NULL COMMENT 'Registering operator',
    updated_at DATETIME(3) NOT NULL COMMENT 'Updated UTC',
    updated_by VARCHAR(64) NULL COMMENT 'Current operator',
    version BIGINT NOT NULL DEFAULT 1 COMMENT 'Guest revision',
    deleted INT NOT NULL DEFAULT 0 COMMENT 'Logical archive',
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_guest_no (tenant_no, guest_no),
    UNIQUE KEY uk_mbr_guest_key (tenant_no, entity_no, store_no, actor_realm, actor_kind, actor_no, key_hash),
    CONSTRAINT ck_mbr_guest_actor CHECK (actor_realm='MERCHANT' AND actor_kind IN ('MCH','USR')),
    CONSTRAINT ck_mbr_guest_state CHECK (
        (state='REGISTERED' AND version=1 AND claimed_user_no IS NULL AND claimed_at IS NULL
            AND claim_key_hash IS NULL AND claim_request_hash IS NULL AND claim_actor_no IS NULL
            AND claim_actor_realm IS NULL AND claim_actor_kind IS NULL)
        OR (state='CLAIMED' AND version=2 AND claimed_user_no IS NOT NULL AND claimed_at IS NOT NULL
            AND claim_key_hash IS NOT NULL AND claim_request_hash IS NOT NULL AND claim_actor_no IS NOT NULL
            AND claim_actor_realm='MERCHANT' AND claim_actor_kind IN ('MCH','USR'))),
    CONSTRAINT ck_mbr_guest_deleted CHECK (deleted IN (0,1))
) ENGINE=InnoDB COMMENT='Shared member guest registration and unique claim';
