-- Platform receiving configuration and manual proof, not a second cash ledger.
ALTER TABLE stl_payment
    ADD COLUMN platform_bank_ref_key VARCHAR(64) DEFAULT NULL,
    ADD COLUMN platform_manual_proof MEDIUMTEXT DEFAULT NULL,
    ADD COLUMN platform_reconciliation_proof MEDIUMTEXT DEFAULT NULL,
    ADD UNIQUE KEY uk_platform_bank_reference (platform_bank_ref_key);

CREATE TABLE pay_platform_collection_head (
    tenant_no VARCHAR(32) NOT NULL,
    profile_no VARCHAR(64) NOT NULL,
    current_version BIGINT NOT NULL,
    enabled TINYINT NOT NULL,
    PRIMARY KEY (tenant_no, profile_no)
);
CREATE TABLE pay_platform_collection_profile (
    tenant_no VARCHAR(32) NOT NULL,
    profile_no VARCHAR(64) NOT NULL,
    profile_version BIGINT NOT NULL,
    instructions_json MEDIUMTEXT NOT NULL,
    configured_by VARCHAR(64) NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (tenant_no, profile_no, profile_version)
);
