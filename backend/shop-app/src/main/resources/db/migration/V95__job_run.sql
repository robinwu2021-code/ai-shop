-- 定时任务的运行记录。
--
-- **为什么需要它**：这一轮撞到过一个形状 —— `OutboxDispatcher` 写好了、
-- 类注释写着「由定时任务调用」，而那个任务从来没被写出来。真实部署里
-- 全站站内信一条都发不出去，测试却全绿（测试自己手动调了投递方法）。
--
-- 有这张表的话，**第一天就会因为「从来没有一条 outbox 任务的运行记录」而暴露**。
-- 它也是 xxl-job 控台里真正被需要的那部分 —— 而它只值一张表，
-- 不值一个必须活着的新服务（见 定时任务清单与调度方案 §2）。
--
-- **一个任务一行，不是一次运行一行**：Outbox 投递每 5 秒一轮，追加式一天 17000 行，
-- 而这张表要回答的问题只有三个 —— 最后一次什么时候跑的、结果如何、是不是在连续失败。
-- 追加式还会让「查最后一次」变成一次排序扫描，而这是运营端列表的默认查询。

CREATE TABLE IF NOT EXISTS sys_job_run (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    job_name             VARCHAR(64)  NOT NULL COMMENT '任务名，与 @SchedulerLock 的 name 一致',
    last_run_at          DATETIME     NOT NULL COMMENT '最后一次开始跑的时刻',
    duration_ms          BIGINT       NOT NULL DEFAULT 0 COMMENT '最后一次耗时',
    status               VARCHAR(16)  NOT NULL COMMENT 'OK / FAILED',
    detail               VARCHAR(255)          COMMENT '这一轮做了什么（「投出 12 条」「关单 3 单」）',
    error                VARCHAR(512)          COMMENT '失败时的原因',
    -- **连续失败次数**：单次失败可能是网络抖动，连续失败才是要人去看的信号。
    -- 成功即清零 —— 累计计数的话，一个跑了半年的任务迟早会攒出一个吓人的数字
    consecutive_failures INT          NOT NULL DEFAULT 0,
    run_count            BIGINT       NOT NULL DEFAULT 0 COMMENT '累计跑过多少轮',
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_name (job_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '定时任务运行记录（一个任务一行）';
