-- 商家违规记录（P-11.1.5）。
--
-- 为什么单独一张表而不是往 mch_entity 上加计数：
-- breach_count 只是**结论**，而处置需要的是**事实**（哪一单、什么时候、谁处理的、
-- 依据是什么）。商家申诉时要核对的正是这些事实 —— 只有一个计数器的话，
-- 运营既说不清那个数字是怎么来的，也没法在申诉成立时准确地减回去。
CREATE TABLE IF NOT EXISTS mch_violation
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    violation_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL COMMENT 'FAKE_GOODS/BREACH/PRICE_FRAUD/SERVICE。**只有 BREACH 计入 breach_count**',
    action VARCHAR(16) NOT NULL COMMENT 'WARN/LIMIT/SUSPEND。SUSPEND 会真的把商家推到 SUSPENDED',
    detail VARCHAR(1024) NOT NULL COMMENT '事实描述与证据出处。必填 —— 没有事实的处置在申诉时站不住',
    operator_no VARCHAR(64) DEFAULT NULL COMMENT '处置人（运营 staffNo）',
    at BIGINT(20) NOT NULL COMMENT '处置时间',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_violation_no (violation_no),
    KEY idx_violation_entity (entity_no, at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='商家违规与处置记录：结论在 mch_entity.breach_count，事实在这里';
