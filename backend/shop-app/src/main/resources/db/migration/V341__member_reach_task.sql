-- 触达效果回看（TDD-会员标签与定向营销 批 C · AC-12/13/14）。
--
-- 此前 mbr_reach_log 只有 task_no，没有标题、受众、计数 ——「发出去的」列表无从列起；
-- opened_at / ordered_at 两列建表至今无人回写，效果页只能是一片零。

-- ── 1. 触达批次头：列表与效果页读它，计数随回写带条件原子累加，不扫明细 ──────────

CREATE TABLE IF NOT EXISTS mbr_reach_task
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    task_no VARCHAR(64) NOT NULL COMMENT '= mbr_reach_log.task_no',
    entity_no VARCHAR(64) NOT NULL COMMENT '商家主体。数据域锚点',
    scene VARCHAR(24) NOT NULL COMMENT 'NOTICE / WAKEUP / COUPON',
    title VARCHAR(64) NOT NULL,
    body VARCHAR(255) DEFAULT NULL,
    audience_json TEXT NOT NULL COMMENT '发送那一刻的受众项 [{type,value}]',
    audience_desc VARCHAR(128) NOT NULL COMMENT '「沉睡 · 爱囤货」—— 列表直接显示，不回头解析受众项',
    matched_count INT(11) NOT NULL DEFAULT 0,
    sent_count INT(11) NOT NULL DEFAULT 0,
    skipped_count INT(11) NOT NULL DEFAULT 0,
    skip_detail VARCHAR(255) DEFAULT NULL COMMENT 'LEAD:2,TOO_SOON:3 —— 与 pmt_coupon_issue 同格式',
    opened_count INT(11) NOT NULL DEFAULT 0 COMMENT '点推送进店的人数。回写影响 1 行时才 +1，重复进店不重复计',
    ordered_count INT(11) NOT NULL DEFAULT 0 COMMENT '窗口内下单的人数。每人只记触达后的第一单',
    ordered_amount_minor BIGINT(20) NOT NULL DEFAULT 0,
    sent_at BIGINT(20) NOT NULL,
    stats_until BIGINT(20) NOT NULL COMMENT 'sent_at + 归因窗口；过了即「已统计」，数字不会再变',
    operator_no VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_reach_task_no (task_no),
    KEY idx_mbr_reach_task_entity (entity_no, sent_at)
) COMMENT='触达批次：发出去的每一次';

-- ── 2. 下单归因：金额，以及哪一单（同一单不重复计） ──────────────────────────────

ALTER TABLE mbr_reach_log
    ADD COLUMN ordered_amount_minor BIGINT(20) DEFAULT NULL COMMENT '归到这次触达的那一单的实付（分）',
    ADD COLUMN ordered_ref VARCHAR(64) DEFAULT NULL COMMENT '归到这次触达的那一单的单号';
