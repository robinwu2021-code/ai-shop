-- Resolve the immutable original industry order by its exact plan identity and source store.
CREATE INDEX ix_industry_source_food_plan ON ord_industry_order_source(tenant_no,entity_no,store_no,demand_plan_hash);
