-- 渠道报文（P2 · TDD-支付域 §「每笔流水对应渠道发送或回调信息」）
--
-- **今天这些报文一行都没存。**通道推过来什么、我方发出去什么，
-- 只有 log.warn 里的一句话；日志会滚、会被采集走、按单号根本查不回来。
--
-- 最需要报文的恰恰是**被拒的那几次**：验签失败、回查失败、
-- 回查说没付、认领不到单号 —— 这四条今天全是「log 一句然后回 FAIL」，
-- 而通道那边会一直重推。运营问「它到底推了什么过来」时无从回答。
--
-- 两条设计上不能动的规矩：
--
-- 1) **报文先落，再处理**，且落在独立事务里。处理失败要回滚业务，
--    但报文必须留下 —— 否则「处理失败的那一次」恰恰是没有记录的那一次。
--    中途抛异常的行会停在 outcome=RECEIVED，那本身就是线索。
--
-- 2) **落库前脱敏**。签名、证书序列号、Authorization 头都不进这张表。
--    代价是这里的报文**不能拿去重放验签** —— 它是排查用的记录，
--    不是通道交互的副本。想验签去通道后台，那边有原件。
--
-- 保留期：这张表随交易量线性增长，由 ChannelMessageRetentionJob 按
-- shop.pay.message-retention-days 清理（默认 90 天，物理删除）。
-- 没有清理的报文表半年后会比 stl_payment 还大，而它的价值只在最近几天。
--
-- 不写 ENGINE / CHARSET / COLLATE：跟随库默认（同 V285）。
CREATE TABLE IF NOT EXISTS stl_channel_message (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    message_no   VARCHAR(40)  NOT NULL COMMENT '报文号 CM…',
    pay_channel  VARCHAR(32)  NOT NULL COMMENT '通道：WECHAT / ALIPAY / STUB',
    msg_type     VARCHAR(16)  NOT NULL COMMENT 'CALLBACK=通道推给我方，SEND=我方发给通道',
    api          VARCHAR(128) NOT NULL COMMENT '回调是端点路径，发送是接口坐标',
    biz_no       VARCHAR(64)  DEFAULT NULL COMMENT '我方单号（out_trade_no / requestNo）。**验签失败时拿不到，故可空**',
    payment_no   VARCHAR(40)  DEFAULT NULL COMMENT '认领到的支付流水。认领之前为空',
    outcome      VARCHAR(24)  NOT NULL COMMENT 'RECEIVED=已落未处理完；ACCEPTED / REJECTED / OK / FAILED',
    reason       VARCHAR(255) DEFAULT NULL COMMENT '拒绝或失败的原因，直接给运营看',
    payload      TEXT         DEFAULT NULL COMMENT '脱敏后的报文，超长截断。**不可用于重放验签**',
    headers      TEXT         DEFAULT NULL COMMENT '脱敏后的请求头，仅回调有',
    tenant_no    VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(64)  DEFAULT NULL,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by   VARCHAR(64)  DEFAULT NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_message_no (message_no),
    -- 「这笔单的报文按时间列出来」是排查时唯一的查法，没有它就是全表扫
    KEY idx_channel_message_biz (biz_no, created_at),
    -- 清理任务按时间删；对账时也要按「某天某通道推了哪些」翻
    KEY idx_channel_message_time (created_at, pay_channel)
) COMMENT='渠道发送与回调报文。归 pay —— D2 拆库时跟着支付域走';
