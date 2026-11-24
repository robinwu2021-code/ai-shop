-- A null location is a legacy record. Backfill only from verified object inventories.
ALTER TABLE sys_media_asset
    ADD COLUMN storage_provider_code VARCHAR(16) NULL COMMENT 'Provider used to persist this object',
    ADD COLUMN storage_location_ref VARCHAR(64) NULL COMMENT 'Stable configured storage location identifier';

ALTER TABLE prn_export_task
    ADD COLUMN storage_provider_code VARCHAR(16) NULL COMMENT 'Provider reserved for this export',
    ADD COLUMN storage_location_ref VARCHAR(64) NULL COMMENT 'Stable configured storage location identifier';

-- Invoice proof history looks up requests by exact store scope, invoice and scene.
CREATE INDEX ix_prn_request_invoice_lookup
    ON prn_request (tenant_no, entity_no, store_no, biz_type, biz_no, scene, id);
