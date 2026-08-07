-- S0 基础设施表（TDD-backend §8）。不属于任何业务域，各模块共写。
-- 幂等写法（IF NOT EXISTS）：开发库可能已被手工建过表，重跑不能炸。

CREATE TABLE IF NOT EXISTS sys_outbox
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_no       VARCHAR(64)  NOT NULL COMMENT '事件业务键',
    aggregate_type VARCHAR(32)  NOT NULL COMMENT '聚合类型 ORDER/SUB_ORDER/...',
    aggregate_id   VARCHAR(64)  NOT NULL COMMENT '聚合业务键',
    event_type     VARCHAR(64)  NOT NULL COMMENT '事件类型 ORDER_PAID/...',
    payload        TEXT         NOT NULL COMMENT 'JSON，自带消费方所需全部字段',
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/FAILED',
    retry_count    INT          NOT NULL DEFAULT 0,
    next_retry_at  DATETIME     NULL,
    last_error     VARCHAR(512) NULL,
    created_at     DATETIME     NOT NULL,
    sent_at        DATETIME     NULL,
    UNIQUE KEY uk_event_no (event_no),
    -- 投递器按 (status, next_retry_at) 捞待发事件，这是它唯一的查询模式
    KEY idx_status_retry (status, next_retry_at),
    KEY idx_aggregate (aggregate_type, aggregate_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '事务性发件箱：业务与事件同事务落库';

CREATE TABLE IF NOT EXISTS sys_idempotent
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    idem_key    VARCHAR(128) NOT NULL COMMENT '客户端 Idempotency-Key',
    endpoint    VARCHAR(128) NOT NULL COMMENT '端点，如 POST /mp/order',
    user_no     VARCHAR(64)  NULL,
    result_json TEXT         NULL COMMENT '首次成功结果快照，重放时原样返回',
    created_at  DATETIME     NOT NULL,
    expire_at   DATETIME     NOT NULL,
    -- ★ 幂等靠这个唯一索引，不靠「先查再插」：并发下先查再插必然失效
    UNIQUE KEY uk_key_endpoint (idem_key, endpoint),
    KEY idx_expire (expire_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '幂等记录：下单/支付/退款/核销必接';
