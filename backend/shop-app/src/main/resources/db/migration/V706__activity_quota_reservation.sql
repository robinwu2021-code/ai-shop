-- Pause activity writers and reservation recovery until the application switches to these tables.
CREATE TABLE pmt_quota (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
    quota_no VARCHAR(64) NOT NULL COMMENT '额度号',
    target_type VARCHAR(24) NOT NULL,
    target_no VARCHAR(64) NOT NULL,
    quota_dimension VARCHAR(16) NOT NULL,
    user_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'TOTAL使用空串，PER_USER使用本人用户号',
    activity_no VARCHAR(64) NOT NULL COMMENT '活动号',
    currency VARCHAR(8) NULL COMMENT '币种发布后不可换账',
    quota_limit BIGINT NULL COMMENT 'NULL不限',
    quota_held BIGINT NOT NULL DEFAULT 0 COMMENT '预留数量',
    quota_used BIGINT NOT NULL DEFAULT 0 COMMENT '实际参与',
    budget_limit_minor BIGINT NULL COMMENT '营销预算NULL不限',
    budget_held_minor BIGINT NOT NULL DEFAULT 0 COMMENT '预留营销额',
    budget_used_minor BIGINT NOT NULL DEFAULT 0 COMMENT '实际营销额',
    per_user_limit INT NULL COMMENT '每人跨版本次数',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '对齐BaseEntity乐观锁',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '对齐BaseEntity，账务事实禁止删除',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC创建',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC更新',
    created_by VARCHAR(64) NOT NULL COMMENT '操作者或系统任务',
    updated_by VARCHAR(64) NOT NULL COMMENT '最后操作者',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_quota_no (tenant_no,quota_no),
    UNIQUE KEY uk_pmt_quota_dimension (tenant_no,target_type,target_no,quota_dimension,user_no),
    CHECK (target_type='ACTIVITY' AND activity_no=target_no),
    CHECK ((quota_dimension='TOTAL' AND user_no='' AND per_user_limit IS NOT NULL AND currency IS NOT NULL)
        OR (quota_dimension='PER_USER' AND user_no<>'')),
    CHECK (quota_held>=0 AND quota_used>=0 AND budget_held_minor>=0 AND budget_used_minor>=0),
    CHECK (quota_limit IS NULL OR quota_held+quota_used<=quota_limit),
    CHECK (budget_limit_minor IS NULL OR budget_held_minor+budget_used_minor<=budget_limit_minor),
    CHECK (per_user_limit>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨版本活动总预算与每人参与额度';

CREATE TABLE pmt_reservation (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
    reservation_no VARCHAR(64) NOT NULL COMMENT '参与预留号',
    reservation_type VARCHAR(16) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_no VARCHAR(64) NOT NULL,
    source_snapshot JSON NOT NULL,
    quota_no VARCHAR(64) NOT NULL,
    source_key CHAR(64) NOT NULL COMMENT '稳定源业务与利益项摘要',
    activity_no VARCHAR(64) NOT NULL COMMENT '活动号',
    version_no VARCHAR(64) NOT NULL COMMENT '规则版本',
    tier_no VARCHAR(64) NULL COMMENT '储值档位',
    user_no VARCHAR(64) NOT NULL COMMENT '受益用户',
    entity_no VARCHAR(64) NOT NULL COMMENT '商家',
    store_no VARCHAR(64) NOT NULL COMMENT '参与门店',
    currency VARCHAR(8) NOT NULL COMMENT '币种',
    quota_units INT NOT NULL COMMENT '名额占用量',
    budget_minor BIGINT NOT NULL COMMENT '营销预算占用',
    status VARCHAR(16) NOT NULL COMMENT 'RESERVED/COMMITTED/RELEASED',
    permit_snapshot JSON NOT NULL COMMENT '不可变权益约定',
    payload_hash CHAR(64) NOT NULL COMMENT '同键异参检查',
    deadline DATETIME(3) NOT NULL COMMENT '超时核查而非直接释放',
    pay_fact_no VARCHAR(64) NULL COMMENT '权威发放或终止事实',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '对齐BaseEntity乐观锁',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '对齐BaseEntity，账务事实禁止删除',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC创建',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC更新',
    created_by VARCHAR(64) NOT NULL COMMENT '操作者或系统任务',
    updated_by VARCHAR(64) NOT NULL COMMENT '最后操作者',
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation (tenant_no,reservation_no),
    UNIQUE KEY uk_reserve_source (tenant_no,reservation_type,source_key),
    KEY ix_reserve_pending (reservation_type,status,deadline,id),
    CHECK (quota_units>0 AND budget_minor>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='营销参与凭证与预算预留';

INSERT INTO pmt_quota (tenant_no,quota_no,target_type,target_no,quota_dimension,
    activity_no,currency,quota_limit,quota_held,quota_used,budget_limit_minor,budget_held_minor,budget_used_minor,per_user_limit,
    version,deleted,created_at,updated_at,created_by,updated_by)
SELECT tenant_no,CONCAT('AB',id),'ACTIVITY',activity_no,'TOTAL',
    activity_no,currency,quota_limit,quota_held,quota_used,budget_limit_minor,budget_held_minor,budget_used_minor,per_user_limit,
    version,deleted,created_at,updated_at,created_by,updated_by
FROM pmt_activity_budget;

INSERT INTO pmt_quota (tenant_no,quota_no,target_type,target_no,quota_dimension,activity_no,user_no,quota_held,quota_used,
    version,deleted,created_at,updated_at,created_by,updated_by)
SELECT tenant_no,CONCAT('AP',id),'ACTIVITY',activity_no,'PER_USER',activity_no,user_no,held_count,used_count,
    version,deleted,created_at,updated_at,created_by,updated_by
FROM pmt_activity_participant;

INSERT INTO pmt_reservation (tenant_no,id,reservation_type,source_type,source_no,source_snapshot,quota_no,
    reservation_no,source_key,activity_no,version_no,tier_no,user_no,entity_no,store_no,currency,quota_units,budget_minor,status,permit_snapshot,payload_hash,deadline,pay_fact_no,
    version,deleted,created_at,updated_at,created_by,updated_by)
SELECT r.tenant_no,r.id,'ACTIVITY',r.permit_snapshot->>'$.sourceRef.sourceType',r.permit_snapshot->>'$.sourceRef.sourceNo',
    r.permit_snapshot->'$.sourceRef', (SELECT q.quota_no FROM pmt_quota q WHERE q.tenant_no=r.tenant_no
        AND q.target_type='ACTIVITY' AND q.target_no=r.activity_no AND q.quota_dimension='TOTAL' AND q.user_no=''),
    r.reservation_no,r.source_key,r.activity_no,r.version_no,r.tier_no,r.user_no,r.entity_no,r.store_no,r.currency,r.quota_units,r.budget_minor,r.status,r.permit_snapshot,r.payload_hash,r.deadline,r.pay_fact_no,
    r.version,r.deleted,r.created_at,r.updated_at,r.created_by,r.updated_by
FROM pmt_activity_reservation r;
