-- Payment owns lifetime template and recipient issuance capacity, independent of marketing budgets.
CREATE TABLE ent_issue_policy (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
    template_no VARCHAR(64) NOT NULL COMMENT '跨版本模板发行控制',
    limit_count BIGINT NULL COMMENT '最大可发份数',
    held_count BIGINT NOT NULL DEFAULT 0 COMMENT '准备中份数',
    issued_count BIGINT NOT NULL DEFAULT 0 COMMENT '实际发行份数',
    max_exposure_minor BIGINT NULL COMMENT '发放授权敞口',
    held_exposure_minor BIGINT NOT NULL DEFAULT 0 COMMENT '占用敞口',
    used_exposure_minor BIGINT NOT NULL DEFAULT 0 COMMENT '已发敞口',
    per_user_limit INT NOT NULL COMMENT '独立领取/发放上限',
    enabled TINYINT NOT NULL DEFAULT 0 COMMENT '发行开关',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '对齐BaseEntity乐观锁',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '对齐BaseEntity，账务事实禁止删除',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC创建',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC更新',
    created_by VARCHAR(64) NOT NULL COMMENT '操作者或系统任务',
    updated_by VARCHAR(64) NOT NULL COMMENT '最后操作者',
    PRIMARY KEY (id),
    UNIQUE KEY uk_issue_policy (tenant_no,template_no),
    CHECK (held_count>=0 AND issued_count>=0 AND held_exposure_minor>=0 AND used_exposure_minor>=0),
    CHECK (limit_count IS NULL OR held_count+issued_count<=limit_count),
    CHECK (max_exposure_minor IS NULL OR held_exposure_minor+used_exposure_minor<=max_exposure_minor)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付发行授权控制，不等同core营销预算';

CREATE TABLE ent_issue_recipient (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    tenant_no VARCHAR(32) NOT NULL COMMENT '租户',
    template_no VARCHAR(64) NOT NULL COMMENT '模板号',
    user_no VARCHAR(64) NOT NULL COMMENT '用户',
    held_count INT NOT NULL DEFAULT 0 COMMENT '待发行份数',
    issued_count INT NOT NULL DEFAULT 0 COMMENT '累计发行份数',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '对齐BaseEntity乐观锁',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '对齐BaseEntity，账务事实禁止删除',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC创建',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC更新',
    created_by VARCHAR(64) NOT NULL COMMENT '操作者或系统任务',
    updated_by VARCHAR(64) NOT NULL COMMENT '最后操作者',
    PRIMARY KEY (id),
    UNIQUE KEY uk_issue_recipient (tenant_no,template_no,user_no),
    CHECK (held_count>=0 AND issued_count>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付模板个人发行限制，防并发多领';
