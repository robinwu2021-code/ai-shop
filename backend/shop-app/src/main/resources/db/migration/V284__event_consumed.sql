-- 事件级幂等：一个事件在一个消费者上只生效一次。
--
-- **与 sys_idempotent 不是一回事，所以不合表。** 那张是**接口级**的：
-- 键是客户端传来的 Idempotency-Key + 端点，服务的是「用户手滑点了两次提交」，
-- 而且它存 result_json 供重放时原样返回。
-- 这张是**域内事件级**的：键是事件号 + 消费者名，服务的是 Outbox 的
-- at-least-once 投递语义 —— 同一个事件会被投第二次，消费者必须自己认得出来。
--
-- 合成一张的表现是：接口级的 24 小时过期规则会把事件记录也清掉，
-- 而 Outbox 的重投可能发生在几天后（消费者一直失败、积压重跑）——
-- 那时它已经忘了自己处理过，于是同一笔钱记两遍。
--
-- **它是 B2/B3 的前置**：把 markPaid 里的发积分与生成结算单改成 Outbox 之后，
-- 「至少一次投递」就成了常态而不是异常，没有这张表，重投一次就多发一次分。

CREATE TABLE IF NOT EXISTS sys_event_consumed
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    event_no VARCHAR(64) NOT NULL COMMENT '事件号（sys_outbox.event_no）',
    handler VARCHAR(128) NOT NULL COMMENT '消费者名。同一个事件被多个消费者各处理一次，是正常的',
    consumed_at DATETIME NOT NULL COMMENT '首次成功处理的时刻',
    PRIMARY KEY (id),
    -- 并发靠唯一索引，不靠先查后插：两个投递线程同时进来时，
    -- 先插成功的执行，后插的撞 1062 —— 那正是「已经处理过」的信号。
    -- 先查后插的话，两个都会查到「没处理过」，然后都执行
    UNIQUE KEY uk_event_handler (event_no,handler),
    -- 清理用。**没有过期时间列**是有意的：事件幂等记录的保留期由清理任务决定，
    -- 写一个 expire_at 进去等于让写入方猜「这条要留多久」，而它不知道
    KEY idx_event_consumed_at (consumed_at)
) COMMENT='事件级幂等：一个事件在一个消费者上只生效一次';

-- **不写 ENGINE / CHARSET / COLLATE**，跟随库默认 —— 与 V280/281/282 同一写法。
--
-- 两个理由，第二个更要紧：
--
-- (1) utf8mb4_uca1400_ai_ci 是 MariaDB 11.4+ 独有的，MySQL 没有这个名字。
--     baseline 里 110 张表都这么写（历史欠账，已在 SQL 方言闸门的清单里），
--     新表不该再加一条。
--
-- (2) **换成 utf8mb4_unicode_520_ci 会引入另一个问题**：本表的 event_no 与
--     sys_outbox.event_no 是同一个键（血缘表里登记了归属），排查
--     「这个事件到底有没有被消费」时第一件事就是按它 join 两张表。
--     两张表排序规则不同的话，那个 join 在 MySQL/MariaDB 上直接报
--     Illegal mix of collations —— 而 H2 根本不解析 COLLATE，本地全绿、连真库才炸。
--
-- 跟随库默认就没有这个问题：它与 sys_outbox 在同一个库里，拿到的是同一个规则。
