-- ai-shop 正式库 ai_shop_ume · OpsKit 审批上线前的迁移 V690、V691（2026-11-23）
-- 应用账号 ai_shop_ume 只有 SELECT/INSERT/UPDATE/DELETE，Flyway 建不了表：由用户 / DBA 在 .61 的 mysql9 容器里以 root 执行，
-- 再登记进 flyway_schema_history（与 V679–V681 同一做法；校验和按 Flyway CRC32 算，已用 near-pay 正式库 V66/V67/V68/V134 核对算法一致）。
--
-- 进入方式（口令经环境变量，不出现在命令行）：
--   sudo /opt/mysql9-runtime/docker -H unix:///run/mysql9-docker.sock exec -it mysql9 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot'
--
-- 先备份会改到的三张表（V690 只新建表；V691 插 sys_function_point / sys_role_point）：
--   sudo /opt/mysql9-runtime/docker -H unix:///run/mysql9-docker.sock exec mysql9 \
--     sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction ai_shop_ume flyway_schema_history sys_function_point sys_role_point' \
--     | gzip > /data0/mysql9/backups/ai_shop_ume-before-V690-20261123.sql.gz

USE ai_shop_ume;

-- ───────── 0. 前置核对 ─────────
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;   -- 期望最高 689，success=1
SELECT COUNT(*) AS should_be_0 FROM information_schema.tables WHERE table_schema='ai_shop_ume' AND table_name='approval_link';
SELECT COUNT(*) AS should_be_0 FROM sys_function_point WHERE point_code='ACT__PAY_FEE_PLAN_APPROVE';

-- ───────── 1. V690 审批关联表（原文照抄 V690__approval_link.sql）─────────
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
 -- 拟定结果（方案-第一批审批工作流 Q7）：审批人在业务页填好的决定内容（如退款金额、核定实收、服务范围），随送审冻结在 OpsKit 字段里，生效时按它执行
 proposal TEXT NULL,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uk_approval_link_subject (subject_type, subject_ref, revision),
 KEY ix_approval_link_instance (instance_no),
 KEY ix_approval_link_pending (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
SELECT MAX(installed_rank)+1, '690', 'approval link', 'SQL', 'V690__approval_link.sql', 684349598, CURRENT_USER(), 0, 1 FROM flyway_schema_history;

-- ───────── 2. V691 审批人权限码 pay:fee-plan:approve 的功能点（原文照抄 V691__pay_fee_plan_approve_point.sql）─────────
-- near-pay 佣金规则发布接入 OpsKit 审批（方案-第一批审批工作流 T4）后，审批人权限码 pay:fee-plan:approve 要能在 ai-shop 角色里授予：
-- OpsKit 按运营员工的权限码派待办。运营端没有对应接口，只给审批人用。
-- 与 V686 同规则：补一个 ACTION 功能点（挂 OPS_FINANCE），授权只给 SUPER_ADMIN，不授给既有岗位。只新增、可重复执行。
-- 码表见 near-pay docs/api/opskit-approver-permission-codes.txt，由 PayOpsPermissionCodesTest 对账。
INSERT INTO sys_function_point(point_code,function_code,name,group_name,href,ui_perm_code,perm_code,backend_status,ui_ready,matrix_code,point_type,sort,created_at,updated_at) SELECT 'ACT__PAY_FEE_PLAN_APPROVE','OPS_FINANCE','佣金规则：审批发布','支付中心',NULL,'pay:fee-plan:approve','pay:fee-plan:approve','IMPLEMENTED',1,NULL,'ACTION',690,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM DUAL WHERE NOT EXISTS(SELECT 1 FROM sys_function_point WHERE perm_code='pay:fee-plan:approve' OR point_code='ACT__PAY_FEE_PLAN_APPROVE');
INSERT INTO sys_role_point(role_code,point_code,end_code,created_at,updated_at) SELECT 'SUPER_ADMIN','ACT__PAY_FEE_PLAN_APPROVE','OPS',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM DUAL WHERE EXISTS(SELECT 1 FROM sys_function_point WHERE point_code='ACT__PAY_FEE_PLAN_APPROVE') AND EXISTS(SELECT 1 FROM sys_role WHERE role_code='SUPER_ADMIN' AND end_code='OPS' AND deleted=0) AND NOT EXISTS(SELECT 1 FROM sys_role_point WHERE role_code='SUPER_ADMIN' AND point_code='ACT__PAY_FEE_PLAN_APPROVE' AND end_code='OPS');

INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
SELECT MAX(installed_rank)+1, '691', 'pay fee plan approve point', 'SQL', 'V691__pay_fee_plan_approve_point.sql', 553967380, CURRENT_USER(), 0, 1 FROM flyway_schema_history;

-- ───────── 3. 核对 ─────────
SELECT version, description, checksum, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;   -- 期望 691、690、689
SHOW CREATE TABLE approval_link;
SELECT point_code, perm_code FROM sys_function_point WHERE point_code='ACT__PAY_FEE_PLAN_APPROVE';
