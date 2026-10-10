-- 资金风控 · 影子期日志。
--
-- 方案见 docs/technical/design/TDD-资金风控方案.md §五。
--
-- **上线前必须先跑影子模式**：只记录会拦谁、不真拦，跑两周看命中的都是什么人，
-- 再定阈值。不先跑就定阈值，等于拿真实商家的货款做实验。
--
-- 这张表是**影子期专用**的：跑完复盘定了阈值之后可以删。
-- 不留的话复盘无从做起，而复盘正是影子模式的全部目的。

CREATE TABLE IF NOT EXISTS pay_risk_shadow_log
(
    id             BIGINT(20)   NOT NULL AUTO_INCREMENT,
    log_no         VARCHAR(64)  NOT NULL,
    entity_no      VARCHAR(64)  NOT NULL,
    batch_no       VARCHAR(64)  NOT NULL COMMENT '判的是哪一批',
    -- 判定结果。影子期这个值不会真的拦住任何东西，只记「如果真拦会怎样」
    verdict        VARCHAR(16)  NOT NULL COMMENT 'PASS / HOLD / HARD_FAIL',
    -- 命中了哪几条规则，逗号分隔。**要能看出是哪一条**，
    -- 否则复盘时只知道「拦了 37 批」，不知道该调哪个阈值
    hit_rules      VARCHAR(255) DEFAULT NULL,
    -- 直接展示给商家的原话（含具体数字与阈值）。影子期不展示，但要记 --
    -- 复盘时要能判断「这句话说得清不清楚」，而不是等真拦了才发现说不清
    explain_text   VARCHAR(512) DEFAULT NULL,
    -- 如果真拦，会拦住多少钱。**复盘的第一个数**
    would_hold_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '本可拦下的金额（分）',
    -- 指标快照：事后要能复算。算不出来的数字在申诉时一文不值
    refund_rate_bp INT(11)      NOT NULL DEFAULT 0 COMMENT '退款率（万分比），-1 = 分母为零不出结论',
    debt_minor     BIGINT(20)   NOT NULL DEFAULT 0 COMMENT '当时的欠款余额（分）',
    deposit_minor  BIGINT(20)   NOT NULL DEFAULT 0 COMMENT '当时的保证金可用（分）',
    tenant_no      VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at     DATETIME     NOT NULL,
    created_by     VARCHAR(64)  DEFAULT NULL,
    updated_at     DATETIME     NOT NULL,
    updated_by     VARCHAR(64)  DEFAULT NULL,
    version        BIGINT(20)   NOT NULL DEFAULT 0,
    deleted        TINYINT(4)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_risk_shadow_no (log_no, tenant_no),
    -- 一批只记一条：截批任务重跑时不该把同一批记两遍，
    -- 否则「命中率」这个复盘判据会被重复计数撑大
    UNIQUE KEY uk_risk_shadow_batch (batch_no, tenant_no, deleted),
    KEY idx_risk_shadow_entity (entity_no, tenant_no)
) COMMENT='资金风控影子期日志。影子期专用，定阈值后可删';
