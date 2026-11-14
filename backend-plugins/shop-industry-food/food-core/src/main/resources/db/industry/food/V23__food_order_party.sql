ALTER TABLE fnb_order_batch ADD COLUMN party_no VARCHAR(32) NULL COMMENT 'Original anonymous dining session; not a platform account' AFTER buyer_no;
CREATE INDEX idx_fnb_batch_party ON fnb_order_batch(tenant_no, entity_no, store_no, party_no);
