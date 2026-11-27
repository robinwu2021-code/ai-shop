ALTER TABLE ent_use_reversal
    ADD COLUMN source_type VARCHAR(16) NULL,
    ADD COLUMN source_no VARCHAR(64) NULL,
    MODIFY COLUMN after_sale_no VARCHAR(64) NULL;
UPDATE ent_use_reversal SET source_type='AFTER_SALE', source_no=after_sale_no;
ALTER TABLE ent_use_reversal
    MODIFY COLUMN source_type VARCHAR(16) NOT NULL,
    MODIFY COLUMN source_no VARCHAR(64) NOT NULL,
    DROP INDEX uk_reverse_fact,
    ADD UNIQUE KEY uk_reverse_source (tenant_no,source_type,source_no,origin_operation_no,instance_no),
    ADD INDEX ix_reverse_usage (tenant_no,usage_no),
    ADD CONSTRAINT ck_reverse_source CHECK (
        (source_type='ORDER_CANCEL' AND after_sale_no IS NULL) OR
        (source_type='AFTER_SALE' AND after_sale_no IS NOT NULL AND after_sale_no=source_no)
    );
