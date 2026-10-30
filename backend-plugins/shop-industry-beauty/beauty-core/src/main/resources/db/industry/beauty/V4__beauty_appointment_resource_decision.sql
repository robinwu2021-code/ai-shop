-- Immutable original source decision fence metadata; V1/V2/V3 remain frozen.
-- Frozen V1 remains unchanged; permission and resource authority live in services.

CREATE TABLE `bty_appointment_resource_decision` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '物理自增主键，不对外',
    `tenant_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'MAIN' COMMENT '沿当前租户上下文；不另启租户体系',
    `entity_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '商家主体范围；不等于租户',
    `created_at` DATETIME(3) NOT NULL COMMENT '创建时刻',
    `created_by` VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '实际操作者业务引用',
    `updated_at` DATETIME(3) NOT NULL COMMENT '更新时间',
    `updated_by` VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '更新操作者',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
    `deleted` INT NOT NULL DEFAULT 0 COMMENT '沿BaseEntity；事实表禁止删除或软删覆盖',
    `intent_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'intent_no',
    `command_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'command_no',
    `decision_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'decision_no',
    `fence_no` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'fence_no',
    `fence_revision` BIGINT NOT NULL COMMENT 'fence_revision',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_bty_appointment_resource_decision_1` (`tenant_no`, `entity_no`, `intent_no`),
    UNIQUE KEY `uk_bty_appointment_resource_decision_2` (`tenant_no`, `entity_no`, `command_no`),
    UNIQUE KEY `uk_bty_appointment_resource_decision_3` (`tenant_no`, `entity_no`, `decision_no`),
    CONSTRAINT `ck_bty_appointment_resource_decision_1` CHECK (`version` >= 0),
    CONSTRAINT `ck_bty_appointment_resource_decision_2` CHECK (`deleted` = 0),
    CONSTRAINT `ck_bty_appointment_resource_decision_3` CHECK (`fence_revision` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
