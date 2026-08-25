-- 触达记录（P7）：**频次闸查它，效果也算它**。
--
-- 为什么不复用 msg_message：那张表记的是「发过什么消息」，而这里要回答的是
-- 「这家店最近有没有打扰过这个人」—— 查询形状是 (主体, 人, 场景, 时间)，
-- 而且要能按批次回看一次群发的结果。混在一张表里，频次闸的那条查询会退化成全表扫。
--
-- ⚠️ 这是整条线上**唯一会打扰真实用户**的一步。灰度口径写在执行方案里：
-- 先只对一家自己的测试商户开，观察一周退订率。
CREATE TABLE IF NOT EXISTS mbr_reach_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    reach_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    member_no VARCHAR(64) NOT NULL,
    segment_no VARCHAR(64) DEFAULT NULL COMMENT '这次发给哪一群人',
    task_no VARCHAR(64) DEFAULT NULL COMMENT '一次群发的批次号',
    channel VARCHAR(16) NOT NULL DEFAULT 'PUSH',
    scene VARCHAR(24) NOT NULL COMMENT 'NOTICE 公告 / WAKEUP 唤回 / COUPON 发券通知。频次闸按场景分档',
    sent_at BIGINT(20) NOT NULL,
    opened_at BIGINT(20) DEFAULT NULL,
    ordered_at BIGINT(20) DEFAULT NULL COMMENT '收到后 7 天内是否下单。效果只认这个 —— 打开率不是生意',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_reach_no (reach_no),
    -- 列序就是频次闸的查询形状：这家店 → 这个人 → 这个场景 → 最近一次
    KEY idx_mbr_reach_gate (entity_no, member_no, scene, sent_at),
    KEY idx_mbr_reach_task (task_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='触达记录：频次闸查它，效果也算它';
