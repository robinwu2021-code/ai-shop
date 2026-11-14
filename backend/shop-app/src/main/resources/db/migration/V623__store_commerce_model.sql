-- Historical stores remain unclassified until a verified migration.
ALTER TABLE mch_store
    ADD COLUMN business_type_code VARCHAR(32) NULL,
    ADD COLUMN commerce_model VARCHAR(16) NULL,
    ADD COLUMN business_type_changed_at BIGINT NULL;
