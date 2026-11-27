ALTER TABLE ent_legacy_coupon_operation ADD COLUMN partial_return_snapshot JSON NULL;
ALTER TABLE ent_use_reversal
    ADD COLUMN source_stage VARCHAR(16) NOT NULL DEFAULT 'RETURN',
    DROP INDEX uk_reverse_source,
    ADD UNIQUE KEY uk_reverse_source_stage (tenant_no,source_type,source_no,origin_operation_no,instance_no,source_stage),
    ADD CONSTRAINT ck_reverse_stage CHECK (source_stage='RETURN' OR
        (source_stage='CLOSURE' AND source_type='AFTER_SALE' AND units=1));
