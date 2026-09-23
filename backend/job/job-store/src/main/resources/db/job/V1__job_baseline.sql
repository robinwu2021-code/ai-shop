-- 定时任务独立库 · 基线
--
-- 这是 ai_shop_job 的第一条迁移，历史表 job_flyway_history，与平台库互不知情。
--
-- 三张表的写者是**各自唯一**的，这条不变式比「独立一个库」更精确，review 时能直接检查：
--   job_definition  只有运营端写，worker 只读
--   job_run         只有 worker 写，运营端只读
--   job_log         只有 worker 写，运营端只读
-- 业务系统三张表一张都不碰 —— 它没有本库的连接串，且账号只授权 ai_shop_job.*。
--
-- 关于 job_definition 的 cron / enabled：**入库即归运营，代码永不覆盖**。
-- 代码只在首次见到 handler 时 INSERT 一次；之后每次启动只更新 display_name / description
-- 这类「只有代码知道」的列。否则运营在页面上改的 cron 会在下次发版被悄悄冲掉 ——
-- 没有报错、没有日志，只是某天起任务又按老点跑了。
--
-- 关于 job_log 的体量：保留 30 天，由 job-log-purge 任务清理（它自己也在清单里）。
-- 高频任务把 log_every_run 置 0，只在状态变化或失败时落一行，否则它会长成本库最大的表。

CREATE TABLE IF NOT EXISTS job_definition
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    job_name         VARCHAR(64)  NOT NULL,
    display_name     VARCHAR(64)  NOT NULL,
    description      VARCHAR(255),
    handler_name     VARCHAR(64)  NOT NULL,
    target           VARCHAR(16)  NOT NULL DEFAULT 'PLATFORM',
    params           TEXT,
    cron             VARCHAR(64)  NOT NULL,
    enabled          TINYINT      NOT NULL DEFAULT 1,
    timeout_sec      INT          NOT NULL DEFAULT 60,
    lock_at_most_sec INT          NOT NULL DEFAULT 1800,
    manual_trigger   TINYINT      NOT NULL DEFAULT 1,
    log_every_run    TINYINT      NOT NULL DEFAULT 1,
    source           VARCHAR(8)   NOT NULL DEFAULT 'CODE',
    missing          TINYINT      NOT NULL DEFAULT 0,
    owner_module     VARCHAR(32),
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by       VARCHAR(64),
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_def_name (job_name),
    KEY idx_job_def_handler (handler_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务定义与配置。运营写，worker 只读';

-- job_name 是「哪一次调度」，handler_name 是「哪一段逻辑」。
-- 两者分开，才能让同一个 handler 配出多个实例：
--   recon-scan-wechat / recon-scan-alipay 同 handler，cron 与 params 不同。
-- handler 只能靠发版新增（逻辑不会凭空产生），job 运营可以在页面上建。

CREATE TABLE IF NOT EXISTS job_run
(
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    job_name             VARCHAR(64) NOT NULL,
    last_run_at          DATETIME,
    last_status          VARCHAR(16),
    duration_ms          BIGINT,
    detail               VARCHAR(500),
    error                VARCHAR(500),
    consecutive_failures INT         NOT NULL DEFAULT 0,
    run_count            BIGINT      NOT NULL DEFAULT 0,
    next_run_at          DATETIME,
    running              TINYINT     NOT NULL DEFAULT 0,
    current_run_id       VARCHAR(40),
    updated_at           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_run_name (job_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务当前状态，每任务一行。worker 写，运营只读';

-- consecutive_failures **只统计 FAILED**。
-- SKIPPED（锁没抢到）是正常的并发保护，TIMEOUT（超时）是结果未知，
-- 把它们算进去，告警就会在一切正常时响 —— 那样的告警等于没有告警。

CREATE TABLE IF NOT EXISTS job_log
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    run_id          VARCHAR(40) NOT NULL,
    job_name        VARCHAR(64) NOT NULL,
    trigger_type    VARCHAR(16) NOT NULL,
    biz_date        DATE,
    started_at      DATETIME    NOT NULL,
    finished_at     DATETIME,
    duration_ms     BIGINT,
    status          VARCHAR(16) NOT NULL,
    detail          VARCHAR(500),
    error           VARCHAR(500),
    worker_instance VARCHAR(64),
    http_status     INT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_log_run (run_id),
    KEY idx_job_log_name_time (job_name, started_at),
    KEY idx_job_log_started (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='执行日志，每轮一行。worker 写，运营只读';

-- idx_job_log_started 是给 job-log-purge 用的：按时间删要走索引，
-- 否则清理任务自己会变成本库最慢的那条 SQL。
