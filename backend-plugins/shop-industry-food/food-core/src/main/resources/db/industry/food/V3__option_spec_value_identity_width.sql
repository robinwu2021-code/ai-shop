-- Preserve V1/V2. Original product dictionary value_no is VARCHAR(64); never truncate its identity.
ALTER TABLE fnb_option_group_item
    MODIFY COLUMN `spec_value_no` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL
        COMMENT '原商品规格字典值 Spec value identity; full original 64 characters';
