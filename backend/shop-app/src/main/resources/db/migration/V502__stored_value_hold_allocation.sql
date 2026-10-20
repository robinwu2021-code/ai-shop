-- Pay money allocations share the existing unified use intent and usage headers.
CREATE TABLE sv_hold_allocation (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
    operation_no VARCHAR(64) NOT NULL COMMENT '统一使用意图号',
    lot_no VARCHAR(64) NOT NULL COMMENT '批次',
    principal_minor BIGINT NOT NULL COMMENT '固化本金分配',
    bonus_minor BIGINT NOT NULL COMMENT '固化赠金分配',
    items_allocation JSON NOT NULL COMMENT '服务行分摊',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '对齐BaseEntity乐观锁',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '对齐BaseEntity，账务事实禁止删除',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC创建',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC更新',
    created_by VARCHAR(64) NOT NULL COMMENT '操作者或系统任务',
    updated_by VARCHAR(64) NOT NULL COMMENT '最后操作者',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sv_alloc (tenant_no,operation_no,lot_no),
    CHECK (principal_minor>=0 AND bonus_minor>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='储值预占分配，状态统一在ent_use_intent';
