-- 审批关联（TDD-OpsKit审批对接 §1.3）：业务对象的一次送审 ↔ OpsKit 审批单。
-- 审批单、步骤、决定都在 OpsKit；这里只是业务侧的投影，用于业务页徽标、按对象查单、发件箱投递与对账。
-- 一次业务修订对应一行（驳回即终态，重新提交 = 新修订 = 新行）。
CREATE TABLE approval_link (
 id BIGINT NOT NULL AUTO_INCREMENT,
 subject_type VARCHAR(64) NOT NULL,
 subject_ref VARCHAR(64) NOT NULL,
 revision BIGINT NOT NULL,
 process_code VARCHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL,
 approval_no VARCHAR(64) NULL,
 instance_no VARCHAR(64) NULL,
 submitted_by VARCHAR(64) NOT NULL,
 submitted_name VARCHAR(128) NULL,
 reason VARCHAR(512) NULL,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uk_approval_link_subject (subject_type, subject_ref, revision),
 KEY ix_approval_link_instance (instance_no),
 KEY ix_approval_link_pending (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
