-- Independent from stock_authority protocol; NULL historical owners require original evidence.
ALTER TABLE ord_create_intent ADD COLUMN inventory_owner VARCHAR(32) NULL;
ALTER TABLE ord_industry_order_source ADD COLUMN inventory_owner VARCHAR(32) NULL;
ALTER TABLE ord_channel_preparation ADD COLUMN inventory_owner VARCHAR(32) NULL;
