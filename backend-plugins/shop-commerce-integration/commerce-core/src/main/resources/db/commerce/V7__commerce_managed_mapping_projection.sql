-- Projection is a durable draft handoff. Original owner/platform verification remains mandatory.
ALTER TABLE cim_sync_run ADD COLUMN mapping_projection_status VARCHAR(24) NOT NULL DEFAULT 'PENDING';
CREATE INDEX idx_cim_run_mapping_projection ON cim_sync_run (tenant_no, mapping_projection_status, status, id);
