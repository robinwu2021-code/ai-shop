-- Preserve the transaction store in immutable effect facts for restricted merchant staff queries.
ALTER TABLE pmt_effect_projection ADD COLUMN store_no VARCHAR(64) NULL COMMENT '源事实发生门店；历史缺失不猜测';
CREATE INDEX ix_effect_store_time ON pmt_effect_projection (tenant_no,entity_no,store_no,occurred_at);
