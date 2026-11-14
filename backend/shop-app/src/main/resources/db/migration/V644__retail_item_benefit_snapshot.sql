-- Freeze the actually selected retail item benefit; item prices already include the benefit.
ALTER TABLE ord_item
    ADD COLUMN activity_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN activity_benefit_type VARCHAR(16) NULL,
    ADD COLUMN activity_benefit_minor BIGINT NOT NULL DEFAULT 0;

-- Null keeps historical order-discount accounting; item benefits occupy budget without another order discount.
ALTER TABLE pmt_apply ADD COLUMN budget_minor BIGINT NULL;
