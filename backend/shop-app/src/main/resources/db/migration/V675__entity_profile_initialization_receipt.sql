-- Only an initialization marker and digest; private profile input remains in PAY Vault.
ALTER TABLE mch_entity_creation_request
 ADD COLUMN initial_profile_hash CHAR(64) NULL,
 ADD COLUMN profile_request_no VARCHAR(64) COLLATE utf8mb4_bin NULL;

-- Only approved public registration facts and controlled proof references are projected into SHOP.
CREATE TABLE mch_entity_profile_projection (
 tenant_no VARCHAR(32) NOT NULL,
 entity_no VARCHAR(64) NOT NULL,
 state VARCHAR(24) NOT NULL,
 next_check_at BIGINT NOT NULL DEFAULT 0,
 observed_actor VARCHAR(64) NOT NULL,
 observed_request_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 observation_generation BIGINT NOT NULL DEFAULT 1,
 approved_revision BIGINT NOT NULL DEFAULT 0,
 approved_ref VARCHAR(80) NULL,
 facts_hash CHAR(64) NULL,
 registration_no VARCHAR(8) NULL,
 evidence_ref VARCHAR(128) NULL,
 PRIMARY KEY(tenant_no,entity_no),
 KEY ix_entity_profile_projection_queue(state,next_check_at,entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
