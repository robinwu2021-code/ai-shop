-- Pay-owned entitlement event source and consumer idempotency ledger (JB-014).
CREATE TABLE pay_ent_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
    event_no VARCHAR(64) NOT NULL COMMENT '事件业务号；系统投递箱复用此号',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型白名单',
    schema_version INT NOT NULL COMMENT '事件载荷版本',
    aggregate_no VARCHAR(64) NOT NULL COMMENT '聚合业务号',
    payload JSON NOT NULL COMMENT '仅白名单事件字段，不含码和凭据',
    payload_hash CHAR(64) NOT NULL COMMENT '稳定事件事实摘要',
    occurred_at DATETIME(3) NOT NULL COMMENT '业务发生时间UTC',
    status VARCHAR(16) NOT NULL COMMENT 'PENDING/SENT/DEAD',
    attempts INT NOT NULL DEFAULT 0 COMMENT '转入统一投递箱的失败次数',
    next_attempt_at DATETIME(3) NOT NULL COMMENT '下次桥接时间UTC',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '事实禁止删除',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC创建',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC更新时间',
    created_by VARCHAR(64) NOT NULL COMMENT '可信操作者或系统',
    updated_by VARCHAR(64) NOT NULL COMMENT '最后操作者或系统',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pay_event (tenant_no,event_no),
    KEY ix_pay_dispatch (status,next_attempt_at),
    CHECK (schema_version > 0 AND attempts >= 0),
    CHECK (status IN ('PENDING','SENT','DEAD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='pay权益事件发件箱；core统一Outbox负责最终投递';

CREATE TABLE pay_ent_inbox (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
    consumer VARCHAR(64) NOT NULL COMMENT 'pay消费者标识',
    event_no VARCHAR(64) NOT NULL COMMENT '来源事件号',
    payload_hash CHAR(64) NOT NULL COMMENT '载荷摘要；同事件异参拒绝',
    processed_at DATETIME(3) NOT NULL COMMENT '与业务变更同事务的处理时刻UTC',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '事实禁止删除',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC创建',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC更新时间',
    created_by VARCHAR(64) NOT NULL COMMENT '可信操作者或系统',
    updated_by VARCHAR(64) NOT NULL COMMENT '最后操作者或系统',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pay_inbox (tenant_no,consumer,event_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='pay跨域事件幂等收件箱';
