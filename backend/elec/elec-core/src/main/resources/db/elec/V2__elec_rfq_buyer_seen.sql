-- 询价单：买家上次看详情时看到了什么（/elec/me 的「询价有新报价」红点，1afe17f2d）。
--
-- 这两列最初写进了 V1 —— 而 V1 在 2026-09-30 08:21 已经在生产执行过（elc_flyway_history 里 checksum -1135822462）。
-- 改已执行的迁移有两个后果：Flyway 校验不过、服务起不来；即便跳过校验，生产库也永远不会有这两列。
-- 所以挪到这里。V1 从此冻结，ElecAppliedMigrationsFrozenTest 钉着它。
ALTER TABLE elc_rfq
    ADD COLUMN buyer_seen_quote_id  BIGINT   DEFAULT NULL COMMENT '买家上次看详情时看到的最大 elc_quote.id。比它大的有效报价 = 新报价（用自增 id 比，不用时间比：报价的 created_at 是库的时钟、quoted_at 是 JVM 的时钟，两边时区不一致时差 8 小时）' AFTER buyer_notified_at,
    ADD COLUMN buyer_seen_quoted_at DATETIME DEFAULT NULL COMMENT '买家上次看详情时 quoted_at 的原样副本。与当前 quoted_at 不等 = 平台报了新价' AFTER buyer_seen_quote_id;
