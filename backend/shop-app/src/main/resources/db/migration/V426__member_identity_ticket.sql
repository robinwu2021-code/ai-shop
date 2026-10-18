-- Unified membership identification only; not a debit authorization or a financial ledger.
CREATE TABLE IF NOT EXISTS mbr_identity_ticket (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
    ticket_hash CHAR(64) NOT NULL COMMENT '随机凭证SHA256，不存原文',
    purpose VARCHAR(24) NOT NULL COMMENT 'MEMBER_CODE/CUSTOMER_REFERENCE',
    user_no VARCHAR(64) NOT NULL COMMENT '顾客用户号',
    entity_no VARCHAR(64) NULL COMMENT '引用绑定商家，统一会员码为空',
    store_no VARCHAR(64) NULL COMMENT '引用绑定门店',
    actor_no VARCHAR(64) NULL COMMENT '引用绑定商家操作人',
    source_ticket_hash CHAR(64) NULL COMMENT '来源会员码摘要，独立过期不依赖父行',
    issued_at DATETIME(3) NOT NULL COMMENT 'UTC签发',
    expires_at DATETIME(3) NOT NULL COMMENT 'UTC到期，半开区间',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC创建',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC更新',
    created_by VARCHAR(64) NOT NULL COMMENT '签发操作人',
    updated_by VARCHAR(64) NOT NULL COMMENT '最后操作者',
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_identity_hash (tenant_no,ticket_hash),
    KEY ix_member_identity_expiry (tenant_no,purpose,expires_at),
    KEY ix_member_identity_user (tenant_no,user_no,purpose),
    CHECK (expires_at>issued_at),
    CHECK ((purpose='MEMBER_CODE' AND entity_no IS NULL AND store_no IS NULL
        AND actor_no IS NULL AND source_ticket_hash IS NULL)
        OR (purpose='CUSTOMER_REFERENCE' AND entity_no IS NOT NULL AND store_no IS NOT NULL
        AND actor_no IS NOT NULL AND source_ticket_hash IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一会员识别凭证，扫码不等于扣款同意';
