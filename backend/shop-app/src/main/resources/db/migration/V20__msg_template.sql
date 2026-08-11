-- 消息模板（P-14.1.1）与消息的模板归属。
--
-- 缺口的来源：msg_subscribe.template_id 一直在引用模板 ID，而**没有任何表管理这些模板**
-- ——模板是谁建的、还启不启用、正文长什么样，全都无处可查。运营想停掉一个扰民的
-- 模板，只能去微信后台改，平台侧完全不知情。

CREATE TABLE IF NOT EXISTS msg_template
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    template_no VARCHAR(64) NOT NULL COMMENT '平台内部单号',
    name VARCHAR(128) NOT NULL,
    channel VARCHAR(16) NOT NULL COMMENT 'SUBSCRIBE 订阅消息 / PUSH App 推送 / INBOX 站内信',
    content VARCHAR(1024) NOT NULL COMMENT '模板正文，含 {占位符}',
    provider_template_id VARCHAR(64) DEFAULT NULL COMMENT '渠道侧模板 ID（如微信的）。站内信为空',
    enabled TINYINT(4) NOT NULL DEFAULT 1 COMMENT '停用后引用它的推送发不出去',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_template_no (template_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息模板。停用即刻生效，引用它的推送发不出去';

-- 消息挂上模板归属。
--
-- **这一列不是为了统计好看，是频控的执行前提**：触达频控里有一条
-- 「同一模板对同一用户的最小间隔」（P-14.1.4），不知道每条消息用的哪个模板，
-- 这条规则根本无法执行 —— 配了也只是个摆设。
ALTER TABLE msg_message
    ADD COLUMN template_no VARCHAR(64) DEFAULT NULL COMMENT '所用模板；系统消息可为空';
