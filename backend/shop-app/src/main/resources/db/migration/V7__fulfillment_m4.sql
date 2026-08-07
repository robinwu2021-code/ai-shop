-- M4.3 履约。
--
-- ⚠️ **刻意不建 `ful_pickup_task` 表**：子订单本身就是履约任务
--    （它已经有 pickup_no、verify_code、status 三个必需字段）。
--    再建一张任务表意味着两处状态要同步 —— 而「订单说已核销、任务说未核销」
--    这类不一致在货架前是没法解释的。履约状态就是子单状态，只有一处。
--
-- 建的是**核销日志**：谁在什么时候核销了哪一单、是不是代核销。
-- 这张表是 append-only 的凭证，不是状态。

CREATE TABLE IF NOT EXISTS ful_verify_log
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- 可空：**失败的核销没有单号**（码不存在时连单都找不到），而失败恰恰是最需要留痕的
    sub_order_no  VARCHAR(64)  NULL,
    pickup_no     VARCHAR(64) NOT NULL,
    verify_code   VARCHAR(16) NOT NULL,
    -- SCAN 扫码 / INPUT 输码 / BATCH 批量 / ON_BEHALF 代核销
    verify_type   VARCHAR(16) NOT NULL DEFAULT 'SCAN',
    operator_no   VARCHAR(64) NOT NULL COMMENT '操作人 userNo —— 代核销必须能追到人',
    result        VARCHAR(24) NOT NULL COMMENT 'SUCCESS 或失败原因',
    at            BIGINT      NOT NULL,
    tenant_no     VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME    NOT NULL,
    KEY idx_pickup_at (pickup_no, at),
    KEY idx_sub_order (sub_order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '核销日志（append-only）';

-- 到货批次（B-10.4）。一期只做「签收」这一步，破损上报留到 M5 售后一起。
CREATE TABLE IF NOT EXISTS ful_batch
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no     VARCHAR(64) NOT NULL,
    pickup_no    VARCHAR(64) NOT NULL,
    arrive_date  VARCHAR(16) NOT NULL COMMENT 'YYYY-MM-DD，按天分批',
    total_qty    INT         NOT NULL DEFAULT 0,
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RECEIVED',
    received_at  BIGINT       NULL,
    received_by  VARCHAR(64)  NULL,
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    created_by   VARCHAR(64)  NULL,
    updated_at   DATETIME    NOT NULL,
    updated_by   VARCHAR(64)  NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    UNIQUE KEY uk_batch_no (batch_no),
    KEY idx_pickup_date (pickup_no, arrive_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '到货批次';
