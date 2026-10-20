-- Core owns order coordination and the price seal, pay continues to own all asset reservations.
CREATE TABLE ord_ent_order_use (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
    operation_no VARCHAR(64) NOT NULL COMMENT '原pay操作号',
    order_no VARCHAR(64) NOT NULL COMMENT '主订单',
    entity_no VARCHAR(64) NOT NULL COMMENT '主体',
    user_no VARCHAR(64) NOT NULL COMMENT '本人',
    source_key CHAR(64) NOT NULL COMMENT '源意图修订键',
    payload_hash CHAR(64) NOT NULL COMMENT '命令摘要',
    context_snapshot JSON NOT NULL COMMENT '原内部操作上下文',
    command_snapshot JSON NOT NULL COMMENT '不可变pay命令',
    order_snapshot_hash CHAR(64) NOT NULL COMMENT '原确认的订单摘要',
    status VARCHAR(24) NOT NULL COMMENT 'PENDING/APPLIED/COMMIT_DECIDED/CANCEL_DECIDED/COMMITTED/RELEASED',
    decision_snapshot JSON NULL COMMENT '持久源决策',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '恢复事实不可删除',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
    created_by VARCHAR(64) NOT NULL COMMENT '操作者',
    updated_by VARCHAR(64) NOT NULL COMMENT '操作者',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_use_operation (tenant_no,operation_no),
    UNIQUE KEY uk_order_use_order (tenant_no,order_no),
    UNIQUE KEY uk_order_use_source (tenant_no,source_key),
    KEY ix_order_use_pending (status,updated_at),
    CHECK (status IN ('PENDING','APPLIED','COMMIT_DECIDED','CANCEL_DECIDED','COMMITTED','RELEASED')),
    CHECK ((status IN ('PENDING','APPLIED') AND decision_snapshot IS NULL)
        OR (status IN ('COMMIT_DECIDED','CANCEL_DECIDED','COMMITTED','RELEASED') AND decision_snapshot IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='核心订单权益协调，不持有权益余额';

CREATE TABLE ord_ent_payment_seal (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
    order_no VARCHAR(64) NOT NULL COMMENT '主订单',
    pay_amount BIGINT NOT NULL COMMENT '收银前封存应付金额',
    currency VARCHAR(8) NOT NULL COMMENT '币种',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '恢复事实不可删除',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
    created_by VARCHAR(64) NOT NULL COMMENT '操作者',
    updated_by VARCHAR(64) NOT NULL COMMENT '操作者',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_payment_seal (tenant_no,order_no),
    CHECK (pay_amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发起支付后禁止改价';
