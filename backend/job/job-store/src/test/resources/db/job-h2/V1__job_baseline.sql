-- 【自动生成，勿手改】由 backend/scripts/gen-test-schema.py 重放 db/migration/V*.sql 得到。
-- 生产是 MySQL 方言；这份是 H2 等价物（去列注释与普通索引，UNIQUE 转 CONSTRAINT）。
-- 与源文件的漂移由 SchemaDriftTest 拦截。


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
    trigger_requested_at DATETIME NULL,
    last_triggered_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_job_def_name UNIQUE (job_name)
);

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
    CONSTRAINT uk_job_run_name UNIQUE (job_name)
);

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
    CONSTRAINT uk_job_log_run UNIQUE (run_id)
);
