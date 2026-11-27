-- 会员营销目标库首批测试结构。所属对象和版本由业务域在同一事务内写入。
CREATE TABLE pmt_activity_scope (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL,
    owner_type VARCHAR(16) NOT NULL COMMENT 'ACTIVITY 活动、ENT_TEMPLATE 卡券模板、ENROLLMENT 报名单',
    owner_no VARCHAR(64) NOT NULL,
    version_no VARCHAR(64) NOT NULL COMMENT '所属版本的原编号；模板整数版本用十进制文本',
    scope_dimension VARCHAR(16) NOT NULL,
    scope_kind VARCHAR(24) NOT NULL,
    ref_no VARCHAR(128) NOT NULL COMMENT 'ALL 用 *；BRAND_STORES 使用不可变的品牌来源编号',
    included TINYINT NOT NULL DEFAULT 1,
    sort_no INT NOT NULL DEFAULT 0,
    rule_snapshot JSON NULL COMMENT '分群规则快照',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_owner_scope (tenant_no, owner_type, owner_no, version_no, scope_dimension, scope_kind, ref_no),
    KEY ix_scope_target (tenant_no, scope_dimension, scope_kind, ref_no, owner_type),
    CHECK (
        (owner_type = 'ACTIVITY' AND (
            (scope_dimension = 'TARGET' AND scope_kind IN ('ALL','STORE','CATEGORY','GOODS')) OR
            (scope_dimension = 'AUDIENCE' AND scope_kind IN ('TAG','LEVEL','SOURCE','SEGMENT','NON_MEMBER'))
        )) OR
        (owner_type = 'ENT_TEMPLATE' AND scope_dimension = 'TARGET'
            AND scope_kind IN ('ALL','STORE','BRAND_STORES','CATEGORY','GOODS','SKU')) OR
        (owner_type = 'ENROLLMENT' AND scope_dimension = 'TARGET' AND scope_kind = 'GOODS')
    ),
    CHECK (scope_kind <> 'ALL' OR ref_no = '*'),
    CHECK (included = 1 OR (owner_type = 'ENT_TEMPLATE' AND included = 0)),
    CHECK (rule_snapshot IS NULL OR scope_kind = 'SEGMENT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
