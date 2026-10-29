ALTER TABLE inv_reservation
    ADD COLUMN source_manifest_hash CHAR(64) NULL,
    ADD COLUMN disposition_revision BIGINT NULL,
    ADD COLUMN confirmation_operation_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT ck_inv_original_manifest CHECK
        ((source_manifest_hash IS NULL AND confirmation_operation_no IS NULL AND disposition_revision IS NULL) OR
         (source_manifest_hash IS NOT NULL AND disposition_revision IS NOT NULL
          AND disposition_revision>=1 AND lifecycle_version IN (1,2)));

ALTER TABLE inv_reservation_line
    ADD COLUMN source_sku_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN source_store_no VARCHAR(32) COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT ck_inv_original_source CHECK
        ((source_sku_no IS NULL AND source_store_no IS NULL) OR
         (source_sku_no IS NOT NULL AND qty>0 AND released_qty>=0 AND issued_qty>=0
          AND issued_qty+released_qty<=qty));
