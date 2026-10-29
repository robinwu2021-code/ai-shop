-- Additive original core receipts; previous food migrations are unchanged.
ALTER TABLE fnb_channel_order_source ADD COLUMN core_preparation_snapshot LONGTEXT NULL;
ALTER TABLE fnb_channel_order_source ADD COLUMN core_acceptance_snapshot LONGTEXT NULL;
