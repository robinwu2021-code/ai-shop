-- Core owns claim source identities, frozen bundles and decisions; no pay balances are stored here.
CREATE TABLE pmt_benefit_intent (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
    intent_no VARCHAR(64) NOT NULL COMMENT '稳定领取意图',
    user_no VARCHAR(64) NOT NULL COMMENT '会话本人',
    request_key_hash CHAR(64) NOT NULL COMMENT '本人幂等键摘要',
    payload_hash CHAR(64) NOT NULL COMMENT '请求摘要',
    status VARCHAR(24) NOT NULL COMMENT 'PENDING/COMMIT_DECIDED/ABORT_DECIDED/COMMITTED/ABORTED',
    plan_snapshot JSON NOT NULL COMMENT '冻结来源、参与许可与子发放命令',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '来源事实不可删除',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
    created_by VARCHAR(64) NOT NULL COMMENT '操作者',
    updated_by VARCHAR(64) NOT NULL COMMENT '操作者',
    PRIMARY KEY (id),
    UNIQUE KEY uk_benefit_intent (tenant_no,intent_no),
    UNIQUE KEY uk_benefit_request (tenant_no,user_no,request_key_hash),
    KEY ix_benefit_pending (status,updated_at),
    CHECK (status IN ('PENDING','COMMIT_DECIDED','ABORT_DECIDED','COMMITTED','ABORTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动或领券中心的真实发放来源';
