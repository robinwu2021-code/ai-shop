-- A brand's legal issuer/sponsor is separate from its participating stores.
-- Existing brands remain unbound until an authorized explicit binding.
ALTER TABLE mch_brand ADD COLUMN legal_entity_no VARCHAR(64) NULL;
CREATE INDEX ix_brand_legal_entity ON mch_brand(tenant_no,legal_entity_no);
