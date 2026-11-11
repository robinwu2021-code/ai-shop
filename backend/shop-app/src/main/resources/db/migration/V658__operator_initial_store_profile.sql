-- Ordinary operator applications retain the store draft and approved store identity.
ALTER TABLE mch_entity_apply
    ADD COLUMN store_profile JSON NULL,
    ADD COLUMN store_no VARCHAR(32) NULL;
