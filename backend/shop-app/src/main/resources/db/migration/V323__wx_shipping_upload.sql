-- 微信发货信息录入的**上报台账**。
--
-- 为什么要一张表，而不是「发货的时候顺手调一下」：
-- 上报是跨网络的副作用，超时、限流、access_token 过期都会造成一次失败，
-- 而**失败的代价是这笔钱结不出来** —— 用户端毫无感知，商家几天后才发现。
-- 没有台账的话，那次失败不留任何痕迹，也没有任何东西会再提起它。
--
-- 台账 + 「10060002 订单已发货当成功」两者合起来才幂等：
-- 只有台账没有那个码的处理，重启后照样会重复调；
-- 只有那个码没有台账，就没法回答「这笔到底报过没有」。
--
-- ⚠️ 一笔订单一行（uk_shipping_order）。拆单发货（delivery_mode=2）
-- 只有快递才允许，我们暂不支持——真要支持时这个唯一键要跟着改，
-- 而那时如果忘了改，第二次上报会被这条键静默挡掉。

CREATE TABLE IF NOT EXISTS trd_shipping_upload
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL,
    -- 支付单的商户单号。上报按它定位微信那笔单（order_number_type=1）——
    -- 用它而不是微信交易号：我们本来就有，少一次查询
    out_trade_no VARCHAR(64) NOT NULL,
    -- 微信四类：1 快递 / 2 同城配送 / 3 虚拟商品 / 4 用户自提。
    -- 映射只此一处，见 WxLogisticsTypes
    logistics_type TINYINT(4) NOT NULL,
    -- PENDING 待上报 / SUCCESS 已上报 / FAILED 不可重试的失败（要人看）
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT(11) NOT NULL DEFAULT 0,
    -- 微信的错误码与原话。**原样存** —— 排查时要的正是那句话，
    -- 翻译成我们自己的说法会把线索丢掉
    err_code INT(11) DEFAULT NULL,
    err_msg VARCHAR(512) DEFAULT NULL,
    uploaded_at DATETIME DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted INT(11) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_shipping_order UNIQUE (order_no, tenant_no, deleted),
    KEY idx_shipping_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='微信发货信息录入的上报台账';
