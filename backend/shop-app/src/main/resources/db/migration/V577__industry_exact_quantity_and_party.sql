-- Exact physical quantity is separate from legacy billing-unit count; no account is invented for a party.
ALTER TABLE ord_item
    ADD COLUMN sale_quantity DECIMAL(20,6) NULL,
    ADD COLUMN quantity_unit VARCHAR(32) NULL,
    ADD COLUMN source_component_ref VARCHAR(64) NULL;
ALTER TABLE ord_industry_order_source
    ADD COLUMN party_no VARCHAR(32) NULL,
    ADD COLUMN party_snapshot JSON NULL,
    ADD COLUMN demand_plan_no VARCHAR(64) NULL,
    ADD COLUMN demand_plan_hash VARCHAR(64) NULL,
    ADD COLUMN demand_plan_snapshot JSON NULL;

ALTER TABLE ord_industry_order_source ADD COLUMN delivery_snapshot LONGTEXT NULL;
