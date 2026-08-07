-- M5.3 售后。**售后是子单粒度**（Q6）：一次售后只针对一个商家，
-- 因为退款要退到那个商家的分账里去，跨商家的「一次退款」在资金上不存在。

CREATE TABLE IF NOT EXISTS ord_after_sale
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    after_sale_no   VARCHAR(64)  NOT NULL,
    sub_order_no    VARCHAR(64)  NOT NULL,
    order_no        VARCHAR(64)  NOT NULL,
    user_no         VARCHAR(64)  NOT NULL,
    merchant_no     VARCHAR(64)  NOT NULL,
    type            VARCHAR(16)  NOT NULL COMMENT 'REFUND_ONLY/RETURN_REFUND/EXCHANGE',
    status          VARCHAR(16)  NOT NULL DEFAULT 'APPLIED',
    reason          VARCHAR(255) NOT NULL,
    images          TEXT          NULL COMMENT 'JSON 数组：凭证图',
    refund_minor    BIGINT       NOT NULL DEFAULT 0,
    -- 极速退：命中阈值自动通过。商家**只可见不可拒**（矩阵 6.2），
    -- 这一位决定了商家端展示「已自动退款」还是「待你处理」
    instant         TINYINT      NOT NULL DEFAULT 0,
    merchant_remark VARCHAR(255)  NULL COMMENT '驳回理由：用户要据此决定是否申诉',
    express_company VARCHAR(64)   NULL,
    express_no      VARCHAR(64)   NULL,
    -- 责任方与出资方（P-6.1.4 / M4 未定）。**先建列**：
    -- 等业务定了再加列，历史售后单没有责任归属，赔付账没法回溯
    liability       VARCHAR(16)   NULL COMMENT 'PLATFORM/MERCHANT/PICKUP',
    -- 分账回退状态：**退款前必须先回退分账**（E4），这一列是那条顺序的落点
    split_reversed  TINYINT      NOT NULL DEFAULT 0,
    refunded_at     BIGINT        NULL,
    tenant_no       VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at      DATETIME     NOT NULL,
    created_by      VARCHAR(64)   NULL,
    updated_at      DATETIME     NOT NULL,
    updated_by      VARCHAR(64)   NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_after_sale_no (after_sale_no),
    -- 一个子单同时只能有一个进行中的售后 —— 由应用层保证（终态可再申请），
    -- 唯一索引做不到「只对进行中的行唯一」
    KEY idx_sub_order (sub_order_no),
    KEY idx_user (user_no, status),
    KEY idx_merchant (merchant_no, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '售后单（子单粒度）';
