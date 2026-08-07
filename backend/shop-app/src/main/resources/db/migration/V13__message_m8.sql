-- M8.3 消息与客服。
--
-- 消息由**领域事件**驱动（支付成功、已取货、退款到账），
-- 所以这张表的写入方是 Outbox 消费者，不是业务代码直接调 —— 见 OutboxDispatcher。

CREATE TABLE IF NOT EXISTS msg_message
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_no  VARCHAR(64)  NOT NULL,
    user_no     VARCHAR(64)  NOT NULL,
    -- TRADE / MARKETING / SYSTEM。三类分开是因为**用户对它们的期待完全不同**：
    -- 交易类必须看到（到货了要去取），活动类可以错过。混在一个列表里，
    -- 交易消息会被活动消息淹没
    msg_type    VARCHAR(16)  NOT NULL,
    title       VARCHAR(128) NOT NULL,
    body        VARCHAR(512)  NULL,
    link        VARCHAR(255)  NULL COMMENT '完整页面路径带参',
    is_read     TINYINT      NOT NULL DEFAULT 0,
    -- 幂等键：同一事件只产生一条消息。事件会重投，用户不该收到两条「支付成功」
    dedup_key   VARCHAR(128)  NULL,
    at          BIGINT       NOT NULL,
    tenant_no   VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at  DATETIME     NOT NULL,
    created_by  VARCHAR(64)   NULL,
    updated_at  DATETIME     NOT NULL,
    updated_by  VARCHAR(64)   NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_message_no (message_no),
    UNIQUE KEY uk_msg_dedup (dedup_key),
    KEY idx_msg_user_read (user_no, is_read, at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '站内消息';

CREATE TABLE IF NOT EXISTS msg_ticket
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_no   VARCHAR(64)  NOT NULL,
    user_no     VARCHAR(64)  NOT NULL,
    subject     VARCHAR(128) NOT NULL,
    content     VARCHAR(1024) NOT NULL,
    order_no    VARCHAR(64)   NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    reply       VARCHAR(1024) NULL,
    replied_at  BIGINT        NULL,
    replied_by  VARCHAR(64)   NULL COMMENT '客服 staffNo —— 代客操作要能追到人',
    tenant_no   VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at  DATETIME     NOT NULL,
    created_by  VARCHAR(64)   NULL,
    updated_at  DATETIME     NOT NULL,
    updated_by  VARCHAR(64)   NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ticket_no (ticket_no),
    KEY idx_ticket_user (user_no, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '客服工单';

-- 订阅消息授权记录（C-MS-01）。**拒绝也要记**：
-- 不记的话每次进页面都会再弹一次授权框，用户会直接把小程序删了。
CREATE TABLE IF NOT EXISTS msg_subscribe
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_no     VARCHAR(64) NOT NULL,
    template_id VARCHAR(64) NOT NULL,
    accepted    TINYINT     NOT NULL DEFAULT 0,
    at          BIGINT      NOT NULL,
    tenant_no   VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at  DATETIME    NOT NULL,
    created_by  VARCHAR(64)  NULL,
    updated_at  DATETIME    NOT NULL,
    updated_by  VARCHAR(64)  NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    UNIQUE KEY uk_sub_user_template (user_no, template_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '订阅消息授权';
