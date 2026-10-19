-- Core product binding and durable original replies; SKU remains the price owner.
CREATE TABLE prd_entitlement_binding (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
    goods_no VARCHAR(64) NOT NULL COMMENT '商品号',
    sku_no VARCHAR(64) NOT NULL COMMENT 'SKU号',
    market VARCHAR(8) NOT NULL COMMENT '实际SKU主市场',
    template_version_no VARCHAR(64) NOT NULL COMMENT 'pay已发布权益定义版本',
    rule_snapshot JSON NOT NULL COMMENT '已发布原规则，用于下单冻结，不实时读草稿',
    rule_hash CHAR(64) NOT NULL COMMENT 'pay已发布原规则摘要',
    units_per_item INT NOT NULL DEFAULT 1 COMMENT '每销售件对应权益实例数',
    currency VARCHAR(8) NOT NULL COMMENT '与商品售价/pay定义一致',
    status VARCHAR(16) NOT NULL COMMENT 'ACTIVE/DISABLED',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '对齐BaseEntity乐观锁',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '对齐BaseEntity，账务事实禁止删除',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC创建',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC更新',
    created_by VARCHAR(64) NOT NULL COMMENT '操作者或系统任务',
    updated_by VARCHAR(64) NOT NULL COMMENT '最后操作者',
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_entitlement (tenant_no,goods_no,sku_no),
    CHECK (units_per_item BETWEEN 1 AND 100),
    CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CARD商品SKU引用支付权益版本，不重复保存余次规则';

CREATE TABLE prd_ent_binding_command (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
    request_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '绑定命令原键',
    payload_hash CHAR(64) NOT NULL COMMENT '原经办主体及参数摘要',
    status VARCHAR(16) NOT NULL COMMENT 'PENDING/COMPLETED',
    response_snapshot JSON NULL COMMENT '成功原回执，不因后续绑定改变',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '幂等事实不删除',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
    created_by VARCHAR(64) NOT NULL COMMENT '操作者',
    updated_by VARCHAR(64) NOT NULL COMMENT '操作者',
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_binding_request (tenant_no,request_key),
    CHECK ((status='PENDING' AND response_snapshot IS NULL)
        OR (status='COMPLETED' AND response_snapshot IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品权益绑定原命令与成功回执';
