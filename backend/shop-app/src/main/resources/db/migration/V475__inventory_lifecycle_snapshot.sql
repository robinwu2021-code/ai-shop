ALTER TABLE ord_sub_order
    ADD COLUMN inventory_lifecycle_version INT NOT NULL DEFAULT 1 COMMENT '1=payment deduction, 2=handoff deduction';

ALTER TABLE prd_stock_lock
    ADD COLUMN fulfillment_ref VARCHAR(64) DEFAULT NULL COMMENT 'Owning merchant sub-order for fulfillment issue';
