CREATE TABLE ent_code_token (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
    token_hash CHAR(64) NOT NULL COMMENT '高熵token摘要',
    instance_no VARCHAR(64) NOT NULL COMMENT '仅对应指定用户券',
    user_no VARCHAR(64) NOT NULL COMMENT '持有人',
    entity_no VARCHAR(64) NOT NULL COMMENT '仅对应指定主体的实例',
    request_key_hash CHAR(64) NOT NULL COMMENT '本人签发幂等键摘要',
    payload_hash CHAR(64) NOT NULL COMMENT '幂等命令摘要',
    nonce CHAR(32) NOT NULL COMMENT '重放签发用随机nonce，不是凭证明文',
    expires_at DATETIME(3) NOT NULL COMMENT '有效期',
    status VARCHAR(16) NOT NULL COMMENT 'ACTIVE/USED/REVOKED',
    consumed_operation_no VARCHAR(64) NULL COMMENT '被哪次意图消费',
    consumed_store_no VARCHAR(64) NULL COMMENT '原使用门店',
    consumed_actor_no VARCHAR(64) NULL COMMENT '原B操作人',
    consumed_at DATETIME(3) NULL COMMENT '定位凭证首次解析UTC时间',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '对齐BaseEntity乐观锁',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '对齐BaseEntity，账务事实禁止删除',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC创建',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC更新',
    created_by VARCHAR(64) NOT NULL COMMENT '操作者或系统任务',
    updated_by VARCHAR(64) NOT NULL COMMENT '最后操作者',
    PRIMARY KEY (id),
    UNIQUE KEY uk_token (tenant_no,token_hash),
    UNIQUE KEY uk_token_request (tenant_no,user_no,request_key_hash),
    KEY ix_token_instance (tenant_no,instance_no,status),
    CHECK (status IN ('ACTIVE','USED','REVOKED')),
    CHECK ((status='USED' AND consumed_operation_no IS NOT NULL AND consumed_store_no IS NOT NULL
        AND consumed_actor_no IS NOT NULL AND consumed_at IS NOT NULL)
        OR (status IN ('ACTIVE','REVOKED') AND consumed_operation_no IS NULL AND consumed_store_no IS NULL
        AND consumed_actor_no IS NULL AND consumed_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='券码定位凭证，存摘要不存明码';
