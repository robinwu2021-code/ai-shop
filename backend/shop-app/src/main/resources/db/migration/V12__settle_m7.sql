-- M7.3 结算与分账（ADR-002）。**按子单结算**：一个子单 = 一个商家 = 一次分账。
--
-- 金额口径（三列各有各的用处，不能合并）：
--   gross      应结基数 = 用户实付 + 平台补贴的优惠（平台券的钱最终要给商家）
--   commission 平台佣金 = 基数 × 费率档（按 traffic_source 分档，R16）
--   net        商家实得 = 基数 - 佣金 - 履约服务费
-- 只存 net 的话，商家问「为什么这单只结了 46 块」时没法拆给他看。

CREATE TABLE IF NOT EXISTS stl_bill
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    settle_no        VARCHAR(64) NOT NULL,
    sub_order_no     VARCHAR(64) NOT NULL,
    order_no         VARCHAR(64) NOT NULL,
    merchant_no      VARCHAR(64) NOT NULL,
    gross_minor      BIGINT      NOT NULL DEFAULT 0,
    commission_minor BIGINT      NOT NULL DEFAULT 0,
    service_fee_minor BIGINT     NOT NULL DEFAULT 0,
    net_minor        BIGINT      NOT NULL DEFAULT 0,
    traffic_source   VARCHAR(24)  NULL,
    commission_rate  INT         NOT NULL DEFAULT 0 COMMENT '万分比，落库快照 —— 费率会变，历史账不能跟着变',
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    split_at         BIGINT       NULL,
    retry_count      INT         NOT NULL DEFAULT 0,
    last_error       VARCHAR(512) NULL,
    tenant_no        VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at       DATETIME    NOT NULL,
    created_by       VARCHAR(64)  NULL,
    updated_at       DATETIME    NOT NULL,
    updated_by       VARCHAR(64)  NULL,
    version          BIGINT      NOT NULL DEFAULT 0,
    deleted          TINYINT     NOT NULL DEFAULT 0,
    UNIQUE KEY uk_settle_no (settle_no),
    -- 一个子单只能有一张结算单：重复生成 = 重复分账 = 给商家多打钱
    UNIQUE KEY uk_sub_order (sub_order_no),
    KEY idx_merchant_status (merchant_no, status),
    KEY idx_status_created (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '结算单（按子单）';

-- 分账指令与回执分开存：指令是我们发的，回执是支付服务商回的，
-- 合在一张表里就无法回答「发过但没回执」这种最需要排查的状态。
CREATE TABLE IF NOT EXISTS stl_split_log
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    settle_no    VARCHAR(64) NOT NULL,
    sub_order_no VARCHAR(64) NOT NULL,
    -- SPLIT 分账 / REVERSE 回退。
    -- 列名不用 `action`：它是 H2 的保留字（MySQL 允许但也不推荐），
    -- 用保留字当列名意味着每个查询都要加引号，迟早有人漏掉一处
    split_action VARCHAR(16) NOT NULL,
    amount_minor BIGINT      NOT NULL,
    -- 平台侧幂等号：与支付服务商的幂等号双保险
    request_no   VARCHAR(64) NOT NULL,
    result       VARCHAR(16) NOT NULL COMMENT 'SUCCESS/FAILED',
    provider_no  VARCHAR(64)  NULL COMMENT '支付服务商回执号',
    message      VARCHAR(512) NULL,
    at           BIGINT      NOT NULL,
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    -- 约束名带表前缀：MySQL 的索引名是**表级**唯一、H2 是 **schema 级**唯一，
    -- 不带前缀的 uk_request_no 会与 mkt_request 的同名约束撞车（MySQL 不报错，H2 直接建不出来）
    UNIQUE KEY uk_split_request_no (request_no),
    KEY idx_settle (settle_no, at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '分账指令与回执（append-only）';
