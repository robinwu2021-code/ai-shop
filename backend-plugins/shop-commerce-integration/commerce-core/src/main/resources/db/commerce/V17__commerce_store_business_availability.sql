-- STORE publications use actual connection/store identity, never an invented SKU mapping.
ALTER TABLE cim_availability_publication MODIFY COLUMN mapping_no VARCHAR(32) NULL;
ALTER TABLE cim_availability_publication MODIFY COLUMN mapping_revision_no VARCHAR(32) NULL;
ALTER TABLE cim_availability_publication ADD COLUMN availability_kind VARCHAR(16) NOT NULL DEFAULT 'STOCK';
ALTER TABLE cim_availability_publication ADD COLUMN next_source_check_at DATETIME(3) NULL;
CREATE INDEX ix_cim_availability_business_due ON cim_availability_publication(availability_kind,next_source_check_at,id);
