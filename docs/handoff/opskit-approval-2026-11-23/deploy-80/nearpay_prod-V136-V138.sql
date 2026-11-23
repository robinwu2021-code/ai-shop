-- near-pay 正式库 nearpay_prod · OpsKit 审批上线前的迁移 V136、V137、V138（2026-11-23）
-- 应用账号 nearpay_prod_app 只有 SELECT/INSERT/UPDATE/DELETE：由用户 / DBA 在 .61 的 mysql9 容器里以 root 执行，
-- 再登记进 flyway_schema_history（与 V134/V135 同一做法：deploy/nearpay/ume-open-api-release/db-80-grant-migrate.sql）。
-- 校验和按 Flyway CRC32 算，已用本库 V66=2097921897、V67=2128838369、V68=336778180、V134=-134895401 核对算法一致。
--
-- 进入方式：
--   sudo /opt/mysql9-runtime/docker -H unix:///run/mysql9-docker.sock exec -it mysql9 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot'
--
-- 先整库备份（约 1 分钟；V137 会改 pay_refund 加一列）：
--   sudo /opt/mysql9-runtime/docker -H unix:///run/mysql9-docker.sock exec mysql9 \
--     sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --source-data=2 --routines nearpay_prod' \
--     | gzip > /data0/mysql9/backups/nearpay_prod-before-V136-20261123.sql.gz

USE nearpay_prod;

-- ───────── 0. 前置核对 ─────────
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;   -- 期望最高 135，success=1
SELECT COUNT(*) AS should_be_0 FROM information_schema.tables
 WHERE table_schema='nearpay_prod' AND table_name IN ('pay_approval_link','pay_approval_event','pay_pending_action');
SELECT COUNT(*) AS should_be_0 FROM information_schema.columns
 WHERE table_schema='nearpay_prod' AND table_name='pay_refund' AND column_name='review_revision';

-- ───────── 1. V136 审批关联表 + 回调去重台账（原文照抄 V136__pay_approval_link.sql）─────────
-- OpsKit 审批关联（方案-第一批审批工作流 §0；与 ai-shop V690 approval_link 同形，表名按本库惯例加 pay_ 前缀）：
-- 业务对象的一次送审 ↔ OpsKit 审批单。审批单、步骤、决定都在 OpsKit；这里只是业务侧的投影，用于按对象查单、送审投递与对账。
-- 一次业务修订对应一行（驳回即终态，重新提交 = 新修订 = 新行）。
-- near-pay 没有通用发件箱：status=PENDING_SUBMIT 的行就是「待投递」，事务提交后同步投递，失败由补送任务重投。
-- 环境隔离：两张表都登记为**共享表**（EnvironmentTablePolicy.SHARED），不带 execution_environment / execution_partition。
-- OpsKit 回调不带环境，回调线程必须先查到关联、才知道该进哪个环境；关联若按环境隔离，SANDBOX 的单在默认环境里永远查不到。
-- 所以关联行自带 environment 列（与 pay_wallet_merchant_identity 同一做法）：送审时记下业务对象所在的执行环境，
-- 投递、执行节点、未通过回调都先绑定它再碰业务表。关联只是路由信息（对象号、修订号、审批单号），不含余额或账户。
-- 对象号来自全局序号（pay_sequence）或全局自增 id，跨环境不重复，所以唯一键不含环境。
CREATE TABLE pay_approval_link (
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
 -- 拟定结果（Q7）：审批人在业务页填好的决定内容，随送审冻结；near-pay 第一批未使用
 proposal TEXT NULL,
 attempts INT NOT NULL DEFAULT 0,
 -- 业务对象所在的执行环境（PRODUCTION / SANDBOX）；未开环境隔离时为 NULL，回调照常走默认环境
 environment VARCHAR(16) NULL,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uk_pay_approval_link_subject (subject_type, subject_ref, revision),
 KEY idx_pay_approval_link_instance (instance_no),
 KEY idx_pay_approval_link_pending (status, updated_at)
);

-- OpsKit 回调去重台账（client 的 EventLedger）：event_key = SHA-256(eventType + ' ' + idemKey) 十六进制。
-- 与业务执行不在同一事务（client 先记台账、再执行、失败再撤销），所以执行动作本身必须幂等。
CREATE TABLE pay_approval_event (
 event_key CHAR(64) NOT NULL,
 consumed_at DATETIME(3) NOT NULL,
 PRIMARY KEY (event_key)
);

INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
SELECT MAX(installed_rank)+1, '136', 'pay approval link', 'SQL', 'V136__pay_approval_link.sql', 1164417781, CURRENT_USER(), 0, 1 FROM flyway_schema_history;

-- ───────── 2. V137 退款复核修订号列（原文照抄 V137__pay_refund_review_revision.sql）─────────
-- 退款复核接入 OpsKit（方案-第一批审批工作流 T2）：送审修订号。
-- pay_refund.version 是部分更新（updateById 只写变动列，不一定 +1），判不出「送审后退款单变过」；
-- review_revision 只在送审与复核结论落地时 +1，执行节点据此判 STALE、重投据此判「已生效」。旧审批中心路径不读不写它。
ALTER TABLE pay_refund ADD COLUMN review_revision BIGINT NOT NULL DEFAULT 0;

INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
SELECT MAX(installed_rank)+1, '137', 'pay refund review revision', 'SQL', 'V137__pay_refund_review_revision.sql', 1302870799, CURRENT_USER(), 0, 1 FROM flyway_schema_history;

-- ───────── 3. V138 待复核表（原文照抄 V138__pay_pending_action.sql）─────────
-- 待复核操作（方案-第二批审批工作流 Q11 / T16）：出款回执 G3、对账差异关闭 G8、人工确认到账 F11 今天点了就生效；
-- 接入 OpsKit 后改为「提交 → 待复核 → 审批通过才执行」。每次提交一行：要做的事（含提交时校验过的对象版本）冻结为 JSON，
-- 审批通过后按冻结内容执行原服务方法；未通过只关闭本行，什么都不做。
-- revision = 本行 version（提交时 0；执行 / 关闭时 +1）。同一对象同一类操作同时只能有一行 PENDING（active_key）。
-- 资金类表，按环境隔离（execution_environment / execution_partition 同 V133）。
CREATE TABLE pay_pending_action (
 id BIGINT NOT NULL AUTO_INCREMENT,
 execution_environment VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
 execution_partition VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'LEGACY',
 CONSTRAINT ck_pay_pending_action_exec_partition CHECK (execution_partition=COALESCE(execution_environment,'LEGACY')),
 tenant_no VARCHAR(32) NOT NULL,
 action_no VARCHAR(40) NOT NULL,
 -- PAYOUT_RECEIPT / RECON_CLOSE / MANUAL_SETTLE
 action_type VARCHAR(32) NOT NULL,
 object_no VARCHAR(64) NOT NULL,
 request_json TEXT NOT NULL,
 request_no VARCHAR(64) NOT NULL,
 request_hash VARCHAR(128) NOT NULL,
 submitted_by VARCHAR(64) NOT NULL,
 reason VARCHAR(255) NOT NULL,
 -- PENDING / APPLIED / REJECTED / CANCELLED
 status VARCHAR(16) NOT NULL,
 -- action_type:object_no，仅 PENDING 时非空
 active_key VARCHAR(100) NULL,
 result_json TEXT NULL,
 closed_reason VARCHAR(255) NULL,
 closed_at DATETIME(3) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uk_pay_pending_action_no (action_no, execution_partition),
 UNIQUE KEY uk_pay_pending_action_request (tenant_no, action_type, request_no, execution_partition),
 UNIQUE KEY uk_pay_pending_action_active (active_key, execution_partition),
 KEY idx_pay_pending_action_object (action_type, object_no)
);

INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
SELECT MAX(installed_rank)+1, '138', 'pay pending action', 'SQL', 'V138__pay_pending_action.sql', 1631216923, CURRENT_USER(), 0, 1 FROM flyway_schema_history;

-- ───────── 4. 核对 ─────────
SELECT version, description, checksum, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 4;   -- 期望 138、137、136、135
SHOW CREATE TABLE pay_approval_link;
SHOW CREATE TABLE pay_pending_action;
SHOW COLUMNS FROM pay_refund LIKE 'review_revision';
