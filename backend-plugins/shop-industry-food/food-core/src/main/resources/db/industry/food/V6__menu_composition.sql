-- Restaurant menu composition only; preserve all applied V1-V5 bytes.
ALTER TABLE fnb_menu_item ADD COLUMN option_mode VARCHAR(16) NOT NULL DEFAULT 'INHERIT' COMMENT 'INHERIT / REPLACE; empty REPLACE forbids options';
ALTER TABLE fnb_menu_item_sku ADD COLUMN recipe_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT 'Same-store same-SKU recipe variant';

CREATE TABLE `fnb_menu_item_option_group` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'BaseEntity自增物理主键，不对外不进URL',
    `tenant_no` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT '沿用BaseEntity，一期MAIN；不是entity_no，不另启租户体系',
    `entity_no` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '当前主体或业务发生时主体快照，不等于账号和支付商户号',
    `created_at` DATETIME(3) NOT NULL COMMENT '沿用MetaObjectHandler及现有LocalDateTime持久化约定',
    `created_by` VARCHAR(32) NOT NULL COMMENT '当前认证业务键；按现有填充规则，作业SYSTEM',
    `updated_at` DATETIME(3) NOT NULL COMMENT '沿用公共填充',
    `updated_by` VARCHAR(32) NOT NULL COMMENT '沿用公共填充',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT 'BaseEntity乐观锁',
    `deleted` INT NOT NULL DEFAULT 0 COMMENT 'BaseEntity逻辑删除；签核和事实表禁止业务归档覆盖历史',
    `menu_item_no` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '餐牌菜项 Item',
    `group_no` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '选配组 Group',
    `sort_no` INT NOT NULL COMMENT '顺序 Sort',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_food_1` (`menu_item_no`, `group_no`),
    KEY `ix_food_audit` (`entity_no`, `created_at`, `id`),
    KEY `ix_food_ref_1` (`entity_no`, `group_no`, `id`)
) ENGINE=InnoDB COMMENT='餐牌菜项选配 Menu item options';
