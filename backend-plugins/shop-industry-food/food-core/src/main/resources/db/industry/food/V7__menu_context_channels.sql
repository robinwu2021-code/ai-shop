-- Menu contexts, verified channel activation and scoped local sales restrictions.
ALTER TABLE fnb_menu_context ADD COLUMN channel_binding_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL, ADD COLUMN channel_scope_key VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'LOCAL', ADD COLUMN price_policy_ref VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;
ALTER TABLE fnb_menu_context DROP INDEX uk_food_1, ADD UNIQUE KEY uk_food_1 (menu_no,order_source,fulfillment,channel_scope_key);
ALTER TABLE fnb_sales_restriction ADD COLUMN order_source VARCHAR(32) NULL, ADD COLUMN fulfillment VARCHAR(32) NULL;

CREATE TABLE `fnb_menu_channel_binding` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'BaseEntity自增物理主键，不对外不进URL',
    `tenant_no` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT '沿用BaseEntity，一期MAIN；不是entity_no，不另启租户体系',
    `entity_no` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '当前主体或业务发生时主体快照，不等于账号和支付商户号',
    `created_at` DATETIME(3) NOT NULL COMMENT '沿用MetaObjectHandler及现有LocalDateTime持久化约定',
    `created_by` VARCHAR(32) NOT NULL COMMENT '当前认证业务键；按现有填充规则，作业SYSTEM',
    `updated_at` DATETIME(3) NOT NULL COMMENT '沿用公共填充',
    `updated_by` VARCHAR(32) NOT NULL COMMENT '沿用公共填充',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT 'BaseEntity乐观锁',
    `deleted` INT NOT NULL DEFAULT 0 COMMENT 'BaseEntity逻辑删除；签核和事实表禁止业务归档覆盖历史',
    `binding_no` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '渠道绑定 Binding',
    `menu_no` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '餐牌 Menu',
    `store_no` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '门店 Store',
    `provider` VARCHAR(32) NOT NULL COMMENT '渠道提供方 Provider',
    `channel_account_ref` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '已有渠道账号引用 Account reference',
    `external_store_ref` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '外部门店 External store',
    `status` VARCHAR(16) NOT NULL COMMENT 'DRAFT / ACTIVE / DISABLED；ACTIVE必须权威验真',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_food_1` (`binding_no`),
    UNIQUE KEY `uk_food_2` (`menu_no`, `store_no`, `provider`, `channel_account_ref`),
    KEY `ix_food_audit` (`entity_no`, `created_at`, `id`),
    KEY `ix_food_store` (`entity_no`, `store_no`, `updated_at`, `id`)
) ENGINE=InnoDB COMMENT='餐牌渠道绑定 Menu channel binding';
