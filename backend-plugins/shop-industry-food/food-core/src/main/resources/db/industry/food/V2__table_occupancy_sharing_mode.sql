-- Preserve the frozen V1. Existing occupancy is conservatively exclusive.
ALTER TABLE fnb_table_occupancy
    ADD COLUMN sharing_mode VARCHAR(16) NOT NULL DEFAULT 'EXCLUSIVE'
        COMMENT 'EXCLUSIVE / SHARED; occupancy arbitration mode, not a payment state',
    ADD CONSTRAINT ck_fnb_occupancy_sharing_mode CHECK (sharing_mode IN ('EXCLUSIVE', 'SHARED'));
