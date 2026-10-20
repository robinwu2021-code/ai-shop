-- Common consumption facts reference the real legacy operation, never a synthetic use intent.
ALTER TABLE ent_usage ADD COLUMN protocol VARCHAR(16) NOT NULL DEFAULT 'STANDARD' COMMENT 'STANDARD/LEGACY';
ALTER TABLE ent_usage ADD COLUMN legacy_instance_no VARCHAR(64) NULL COMMENT '旧券真实支付实例';
ALTER TABLE ent_usage MODIFY COLUMN store_no VARCHAR(64) NULL COMMENT '真实单门店，跨门店或未知为空';
ALTER TABLE ent_usage MODIFY COLUMN store_name VARCHAR(100) NULL COMMENT '发生时已知门店名，未知为空';
ALTER TABLE ent_usage ADD CONSTRAINT ck_usage_protocol CHECK (
    (protocol='STANDARD' AND legacy_instance_no IS NULL AND store_no IS NOT NULL AND store_name IS NOT NULL)
    OR (protocol='LEGACY' AND legacy_instance_no IS NOT NULL AND asset_kind='ORDER_COUPON'
        AND currency IS NOT NULL AND currency='CNY' AND units IS NOT NULL AND units=1 AND amount_minor IS NOT NULL AND amount_minor>0 AND source_order_no IS NOT NULL)
);
CREATE INDEX ix_usage_legacy_instance ON ent_usage(tenant_no,user_no,legacy_instance_no,occurred_at,usage_no);
