-- A sub-order can be fulfilled in several idempotent operations.  The service
-- serializes preparation on the original order/sub-order rows and rejects an
-- unresolved operation; fulfillment_no and operation_key remain unique.
ALTER TABLE ord_industry_fulfillment
    DROP INDEX uk_industry_fulfillment_sub,
    ADD INDEX ix_industry_fulfillment_sub (tenant_no, sub_order_no);
