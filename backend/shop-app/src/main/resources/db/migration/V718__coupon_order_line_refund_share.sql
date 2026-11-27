ALTER TABLE ord_item ADD COLUMN coupon_share_minor BIGINT NULL;
ALTER TABLE ord_after_sale_item ADD COLUMN coupon_return_minor BIGINT NULL;
ALTER TABLE ord_legacy_coupon_checkout ADD COLUMN partial_return_snapshot JSON NULL;
