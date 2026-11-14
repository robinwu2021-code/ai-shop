-- Canonical resources shared by retail, dining and beauty; no capacity arbitration is introduced here.
CREATE TABLE mch_resource (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '物理自增主键，不对外',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN' COMMENT '沿当前租户上下文',
    entity_no VARCHAR(32) NOT NULL COMMENT '经营主体范围',
    created_at DATETIME(3) NOT NULL COMMENT '创建时刻',
    created_by VARCHAR(64) NULL COMMENT '实际操作者业务引用',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    updated_by VARCHAR(64) NULL COMMENT '更新操作者',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
    deleted INT NOT NULL DEFAULT 0 COMMENT '沿BaseEntity，唯一身份不随软删释放',
    resource_no VARCHAR(32) NOT NULL COMMENT '资源号',
    store_no VARCHAR(32) NULL COMMENT '物理资源门店，STAFF为空',
    resource_type VARCHAR(24) NOT NULL COMMENT 'STAFF或ROOM或SEAT或DEVICE',
    personnel_no VARCHAR(32) NULL COMMENT 'STAFF人员引用',
    name VARCHAR(64) NOT NULL COMMENT '资源名',
    capacity INT NOT NULL COMMENT '并发上限',
    status VARCHAR(24) NOT NULL COMMENT 'ACTIVE或SUSPENDED',
    open_occupation_no VARCHAR(32) NULL COMMENT '当前独占式无确定结束占用指针',
    PRIMARY KEY (id),
    UNIQUE KEY uk_mch_resource_no (tenant_no, entity_no, resource_no),
    UNIQUE KEY uk_mch_resource_personnel (tenant_no, entity_no, personnel_no),
    KEY idx_mch_resource_store (tenant_no, entity_no, store_no, resource_type, status),
    CONSTRAINT ck_mch_resource_version CHECK (version >= 0),
    CONSTRAINT ck_mch_resource_deleted CHECK (deleted IN (0,1)),
    CONSTRAINT ck_mch_resource_type CHECK (resource_type IN ('STAFF','ROOM','SEAT','DEVICE')),
    CONSTRAINT ck_mch_resource_status CHECK (status IN ('ACTIVE','SUSPENDED')),
    CONSTRAINT ck_mch_resource_capacity CHECK (capacity > 0),
    CONSTRAINT ck_mch_resource_shape CHECK (
        (resource_type='STAFF' AND store_no IS NULL AND personnel_no IS NOT NULL
            AND CHAR_LENGTH(TRIM(personnel_no)) > 0 AND capacity=1)
        OR (resource_type IN ('ROOM','SEAT','DEVICE') AND store_no IS NOT NULL
            AND CHAR_LENGTH(TRIM(store_no)) > 0 AND personnel_no IS NULL)
    ),
    CONSTRAINT ck_mch_resource_identity CHECK (CHAR_LENGTH(TRIM(resource_no)) > 0),
    CONSTRAINT ck_mch_resource_open_ref CHECK (
        open_occupation_no IS NULL OR CHAR_LENGTH(TRIM(open_occupation_no)) > 0
    )
) ENGINE=InnoDB COMMENT='人员或物理资源';
