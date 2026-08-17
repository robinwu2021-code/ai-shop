-- 平台营销广播「推送任务」（设计：触达推送中台-模块抽象 · N6）。
--
-- 与事件驱动触达（钱扣了/货到了，NotificationConsumer）不同：这是运营**主动发起**的一次
-- 群发 —— 圈一批人、预估触达、定时下发。一次任务一行，可查「谁发的、发给多少、到了多少」。
--
-- status：QUEUED 待发（到点由 worker 捡起）/ RUNNING 下发中 / DONE 完成 / CANCELLED 已取消。
-- 频道一期只做 PUSH（App 推送是营销广播最自然的载体，且 opt-in——有 token 才收）。
CREATE TABLE IF NOT EXISTS notify_push_task
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    task_no VARCHAR(48) NOT NULL COMMENT '任务号',
    name VARCHAR(128) NOT NULL COMMENT '任务名（运营自己看的）',
    audience_type VARCHAR(32) NOT NULL COMMENT '人群 ALL_APP_USER 等',
    channel VARCHAR(16) NOT NULL DEFAULT 'PUSH' COMMENT '下发通道，一期仅 PUSH',
    title VARCHAR(128) NOT NULL,
    body VARCHAR(512) NOT NULL,
    link VARCHAR(256) DEFAULT NULL COMMENT '点开落点，如 /pages/activity/xxx',
    scheduled_at DATETIME DEFAULT NULL COMMENT '定时下发时刻；空=尽快发',
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED/RUNNING/DONE/CANCELLED',
    estimated_count INT(11) NOT NULL DEFAULT 0 COMMENT '创建时预估触达人数',
    sent_count INT(11) NOT NULL DEFAULT 0 COMMENT '实际发出条数',
    finished_at DATETIME DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_push_task_no (task_no),
    KEY idx_push_task_due (status, scheduled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='平台营销广播推送任务';
