-- 店铺门面内容审核（P-10.1 / B-11.2）。
--
-- 背景：商家保存店招与公告此前**直接生效，从不产生待审记录** ——
-- 运营端的审核台建好了，却永远没有东西送进来。
--
-- ⚠️ 这里刻意**不是「全部先审后发」**：公告是店主自发的短文本（「今日到货」
-- 「土鸡蛋还有两筐」），走一遍人审要几小时，那等于这个功能没用。
-- 采用「机审放行 + 命中才人审」：
--   没命中敏感词 → 立刻生效，不进队列
--   命中         → 内容**不生效**（保留旧值），进人审队列，商家看到「审核中」
-- 机审命中项随审核单一起存下来 —— 人审要看到「机器为什么标它」，
-- 否则只能凭感觉判，同一类内容两个人两个结论。
CREATE TABLE IF NOT EXISTS mch_store_audit
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    audit_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL COMMENT 'BANNER（店招图）/ NOTICE（公告文本）',
    content VARCHAR(1024) NOT NULL COMMENT '待审内容：店招图 URL 或公告原文',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PASSED/REJECTED',
    hits VARCHAR(512) DEFAULT NULL COMMENT 'JSON 数组：机审命中的敏感词。人审要看到机器为什么标它',
    submitted_at BIGINT(20) NOT NULL,
    reason VARCHAR(512) DEFAULT NULL COMMENT '驳回原因。**原样出现在商家 B 端**，所以驳回必须填',
    decided_at BIGINT(20) DEFAULT NULL,
    decided_by VARCHAR(64) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_store_audit_no (audit_no),
    KEY idx_store_audit_status (status, submitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='店招与公告的人审队列：只有机审命中的才进来';

-- 敏感词表放在 sys_setting 里而不是硬编码：运营要能随时加词，加词不该等发版。
INSERT INTO sys_setting
(setting_key, setting_value, remark, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('store.sensitive-words',
 '["最低价","全网第一","国家级","绝对","包治","微信","加V","私聊"]',
 '店招与公告的机审词表。命中即转人审（不是直接拒）—— 词表总会误伤，人审是纠偏的那一层',
 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
