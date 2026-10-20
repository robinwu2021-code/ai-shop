-- A certified cancellation may arrive before prepare. It must leave a durable negative fact.
CREATE TABLE pay_ent_source_fence (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
    purpose VARCHAR(16) NOT NULL COMMENT 'GRANT或USE',
    source_key CHAR(64) NOT NULL COMMENT '发行稳定键或使用修订键',
    operation_no VARCHAR(64) NOT NULL COMMENT '原操作号，禁止换号重建',
    source_snapshot JSON NOT NULL COMMENT '原可信来源标识',
    user_no VARCHAR(64) NOT NULL COMMENT '受益人',
    entity_no VARCHAR(64) NOT NULL COMMENT '归属主体',
    status VARCHAR(16) NOT NULL COMMENT 'OPEN或CANCELLED',
    decision_snapshot JSON NULL COMMENT '已核实的源取消事实，不是客户端声明',
    cancelled_at DATETIME(3) NULL COMMENT 'UTC取消事实时间',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '恢复证明不得删除',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
    created_by VARCHAR(64) NOT NULL COMMENT '经办者',
    updated_by VARCHAR(64) NOT NULL COMMENT '经办者',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ent_fence_source (tenant_no,purpose,source_key),
    UNIQUE KEY uk_ent_fence_operation (tenant_no,purpose,operation_no),
    CHECK (status IN ('OPEN','CANCELLED')),
    CHECK ((status='OPEN' AND decision_snapshot IS NULL AND cancelled_at IS NULL)
        OR (status='CANCELLED' AND decision_snapshot IS NOT NULL AND cancelled_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='防止已取消来源被晚到的prepare再次预占';
