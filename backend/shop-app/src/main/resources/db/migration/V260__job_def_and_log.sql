-- 定时任务模块：配置表 + 执行日志表。
--
-- 与既有 sys_job_run（V95）的分工，三张表各答一个问题：
--   sys_job_def  「这个任务该怎么跑」   —— 配置，运营可改，每任务一行
--   sys_job_run  「它现在是什么状态」   —— 当前状态，每任务一行（V95 已有，不改）
--   sys_job_log  「它每一次跑了什么」   —— 执行历史，每次一行
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么 def 表不写种子
-- ─────────────────────────────────────────────────────────────────────────────
-- 14 个任务的定义（名字、默认 cron、锁时长、属于哪个模块）**源头在代码里**，
-- 启动时由 JobRegistry upsert 进来。表只承载「运营改过的那部分」。
--
-- 反过来做（迁移里写死 14 行种子）的代价：以后每加一个任务都要写一支迁移，
-- 而忘了写的那个任务会**静默不注册** —— 表里没有它，注册表就不调度它，
-- 且没有任何地方会报错。让代码当源头则新任务一上线就自动出现在列表里。
--
-- upsert 的口径是「只补不覆盖」：已存在的行只更新 display_name / description /
-- owner_module 这些纯展示字段，**不动 cron 与 enabled** —— 那两个是运营调过的，
-- 每次发布把它们冲回默认值，等于运营的调整全部作废，而且他不会收到任何提示。
-- ─────────────────────────────────────────────────────────────────────────────
-- manual_trigger 与 log_every_run 两列的理由
-- ─────────────────────────────────────────────────────────────────────────────
-- **手动触发按钮给不给**：秒级任务（outbox 5 秒一轮）给这个按钮没有意义 ——
-- 等一下它自己就跑了，而连点只会被 ShedLock 挡回来，看起来像坏了。
-- 按频率在注册时决定，运营改不了。
--
-- **每一轮都写日志，还是只在「状态变化或失败」时写**：5 秒一轮的任务一天 17280 行，
-- 两个这样的一年 1200 万行 —— 那会让 sys_job_log 长成这个库里最大的表，
-- 而其中 99.99% 是「没事发生」。所以秒级任务注册时置 0：
-- 只在**由成功转失败、由失败转成功、或失败**时落一行；分钟级及以上置 1，每轮都落。
--
-- ⚠️ 这两段说明**必须写在建表语句外面**。写在列定义之间的话，
-- gen-test-schema.py 会把紧跟其后的那一列**静默丢掉** —— 本文件第一版就是这么写的，
-- 生成出来的 schema-test.sql 里 manual_trigger 与 log_every_run 两列都不见了，
-- 靠 SchemaDriftTest 才发现。它不报错，只是少两列。
CREATE TABLE IF NOT EXISTS sys_job_def
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    job_name         VARCHAR(64)  NOT NULL COMMENT '任务名。与 @SchedulerLock 的 name、sys_job_run.job_name 一致，是三张表的连接键',
    display_name     VARCHAR(64)  NOT NULL COMMENT '给运营看的中文名，如「套餐到期扫描」',
    owner_module     VARCHAR(32)  NOT NULL COMMENT '属于哪个模块（shop-settle 等），排查时知道找谁',
    cron             VARCHAR(64)  NOT NULL COMMENT 'Spring cron 表达式（6 段）。改它立即生效，不重启',
    enabled          TINYINT      NOT NULL DEFAULT 1 COMMENT '1=调度中 0=已停。停 = 取消 ScheduledFuture，不是让它空跑一趟',
    lock_at_most_sec INT          NOT NULL DEFAULT 1800 COMMENT 'ShedLock 最长持锁秒数。进程被杀后锁最多卡这么久',
    manual_trigger   TINYINT      NOT NULL DEFAULT 1 COMMENT '1=运营端显示「立即执行」按钮。秒级任务注册时置 0，理由见建表前的说明',
    log_every_run    TINYINT      NOT NULL DEFAULT 1 COMMENT '0=只在状态变化或失败时落日志（秒级任务用），理由见建表前的说明',
    description      VARCHAR(255)          COMMENT '这个任务干什么。运营页面直接显示，不要写成代码注释的口气',
    tenant_no        VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by       VARCHAR(64)           COMMENT '最后改动的人。「谁把这个任务关了」必须查得到',
    version          BIGINT       NOT NULL DEFAULT 0,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_def_name (job_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务定义与配置。代码是源头，本表是运营可调的覆盖层';

-- ─────────────────────────────────────────────────────────────────────────────
-- 执行日志
-- ─────────────────────────────────────────────────────────────────────────────
-- 与 sys_job_run 的关系：那张是「最后一次怎么样」（每任务一行、被覆盖），
-- 这张是「每一次怎么样」（追加）。排查「上周四凌晨那次为什么失败」只能靠这张。
--
-- ⚠️ 这张表会长。保留策略与表一起定，不要等它长起来再说：
--   · 秒级任务 log_every_run=0，只在状态变化或失败时落 —— 平时一天 0 行
--   · 统一保留 30 天，由 job-log-purge 任务清理（它自己也在任务清单里）
CREATE TABLE IF NOT EXISTS sys_job_log
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    job_name     VARCHAR(64)  NOT NULL,
    started_at   DATETIME(3)  NOT NULL COMMENT '开始时刻。毫秒精度 —— 秒级任务一秒内可能有两条',
    duration_ms  BIGINT       NOT NULL DEFAULT 0,
    status       VARCHAR(16)  NOT NULL COMMENT 'OK / FAILED / SKIPPED（没抢到锁）',
    detail       VARCHAR(255)          COMMENT '这一轮做了什么，人话：「投出 12 条」「关单 3 单」「无事可做」。运营页面上唯一能让人不看代码就明白发生了什么的一列',
    error        VARCHAR(1024)         COMMENT '失败原因。比 sys_job_run.error 长一倍 —— 那张只留最后一次，这张要留得下堆栈头部',
    trigger_type VARCHAR(16)  NOT NULL DEFAULT 'CRON' COMMENT 'CRON 定时触发 / MANUAL 运营点的。出事时要分得清是不是人点出来的',
    instance     VARCHAR(64)           COMMENT '哪个实例跑的。多实例时「两边都在跑」这种事只能靠它看出来',
    tenant_no    VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 运营页面永远是「按任务看最近几条」，这个复合索引正对那个查询
    KEY idx_job_log_name_time (job_name, started_at),
    -- 清理任务按时间扫全表，单独给一条
    KEY idx_job_log_time (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务执行日志。每次一行，保留 30 天';
