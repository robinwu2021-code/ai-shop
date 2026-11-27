-- 轻量审批 sys_approval（TDD-基础能力库对齐 §2.2 1e，Q13、Q14）：店内、商户侧的快速审批，改价是第一个用户。
-- 表属于基础能力库；基础能力库句柄（Q2）落地前与其他公共能力表一样建在主库。字段照基础能力库表字段设计；
-- 唯一键多一条「对象 + 修订」：同一修订重复送审返回原审批单（幂等），这是设计里“提交时的版本”的用法。
--
-- 改价的审批事实搬到这里，ord_manual_price_adjustment 只留申请本身：状态、批准方式、批准人、批准时间四列删掉。
-- 该表线上 0 行（db-split-manifest），不搬数据。
-- 带 DDL：166 应用库账号无建表 / 改表权限，需 root 先执行。

CREATE TABLE sys_approval (
    id BIGINT NOT NULL AUTO_INCREMENT,
    approval_no VARCHAR(64) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    entity_no VARCHAR(32) NULL,
    store_no VARCHAR(32) NULL,
    process_code VARCHAR(64) NOT NULL,
    subject_type VARCHAR(64) NOT NULL,
    subject_ref VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    amount_minor BIGINT NULL,
    currency CHAR(3) NULL,
    reason_code VARCHAR(32) NULL,
    reason VARCHAR(512) NULL,
    proposal TEXT NULL,
    submitted_by VARCHAR(64) NOT NULL,
    submitted_name VARCHAR(128) NULL,
    approval_mode VARCHAR(24) NOT NULL,
    approved_via VARCHAR(16) NULL,
    status VARCHAR(24) NOT NULL,
    approver_no VARCHAR(64) NULL,
    approver_name VARCHAR(128) NULL,
    decided_at DATETIME(3) NULL,
    decision_comment VARCHAR(255) NULL,
    expires_at DATETIME(3) NULL,
    consumed_at DATETIME(3) NULL,
    result_ref VARCHAR(64) NULL,
    instance_no VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_approval_no (approval_no),
    UNIQUE KEY uk_sys_approval_subject_revision (tenant_no, subject_type, subject_ref, revision),
    KEY ix_sys_approval_store_status (tenant_no, store_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='轻量审批：谁对哪个业务对象提了申请、谁批的、结果';

ALTER TABLE ord_manual_price_adjustment DROP COLUMN status;

ALTER TABLE ord_manual_price_adjustment DROP COLUMN approval_mode;

ALTER TABLE ord_manual_price_adjustment DROP COLUMN approved_by;

ALTER TABLE ord_manual_price_adjustment DROP COLUMN approved_at;
