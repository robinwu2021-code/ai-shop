-- M9a.3 平台端骨架：员工、角色、审计、入驻申请。
--
-- 运营账号与 C 端用户是**两套体系**（realm=OPERATOR vs CONSUMER）：
-- 同一个人可能既是运营也是消费者，但两个身份的权限、会话、审计完全独立。
-- 合成一张表的话，「给自己加个管理员角色」会变成一次普通的用户更新。

CREATE TABLE IF NOT EXISTS sys_staff
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    staff_no   VARCHAR(64)  NOT NULL,
    username   VARCHAR(64)  NOT NULL,
    -- 生产存 bcrypt；一期先存哈希占位，接 auth-core 后替换
    password   VARCHAR(128) NOT NULL,
    real_name  VARCHAR(64)   NULL,
    roles      VARCHAR(255)  NULL COMMENT 'JSON 数组：角色码',
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    tenant_no  VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at DATETIME     NOT NULL,
    created_by VARCHAR(64)   NULL,
    updated_at DATETIME     NOT NULL,
    updated_by VARCHAR(64)   NULL,
    version    BIGINT       NOT NULL DEFAULT 0,
    deleted    TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_staff_no (staff_no),
    UNIQUE KEY uk_staff_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '运营员工';

-- 审计日志。**高危操作必须留痕**（P-1.1.4）：审核、封禁、打款、改价、代客操作。
-- append-only：能改审计的审计等于没有审计。
CREATE TABLE IF NOT EXISTS sys_audit_log
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    staff_no   VARCHAR(64)  NOT NULL,
    staff_name VARCHAR(64)   NULL,
    op_action  VARCHAR(64)  NOT NULL COMMENT '操作码，如 MERCHANT_AUDIT',
    target     VARCHAR(128)  NULL COMMENT '被操作对象的业务键',
    detail     VARCHAR(512)  NULL,
    at         BIGINT       NOT NULL,
    tenant_no  VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at DATETIME     NOT NULL,
    KEY idx_audit_staff_at (staff_no, at),
    KEY idx_audit_target (target)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '操作审计（append-only）';

-- 入驻申请。与 usr_merchant 分开：申请可以被驳回后重提，
-- 而商家主体一旦创建就有了业务数据，不该跟着申请状态来回变。
CREATE TABLE IF NOT EXISTS usr_merchant_apply
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    apply_no       VARCHAR(64)  NOT NULL,
    user_no        VARCHAR(64)  NOT NULL COMMENT '申请人（C 端用户）',
    merchant_no    VARCHAR(64)   NULL COMMENT '审核通过后回填',
    name           VARCHAR(128) NOT NULL,
    merchant_type  VARCHAR(16)  NOT NULL DEFAULT 'PERSONAL',
    contact_phone  VARCHAR(32)   NULL,
    qualifications TEXT          NULL COMMENT 'JSON 数组：资质图',
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
    reject_reason  VARCHAR(255)  NULL,
    audited_by     VARCHAR(64)   NULL,
    audited_at     BIGINT        NULL,
    tenant_no      VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at     DATETIME     NOT NULL,
    created_by     VARCHAR(64)   NULL,
    updated_at     DATETIME     NOT NULL,
    updated_by     VARCHAR(64)   NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_apply_no (apply_no),
    KEY idx_apply_status (status, created_at),
    KEY idx_apply_user (user_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '商家入驻申请';
