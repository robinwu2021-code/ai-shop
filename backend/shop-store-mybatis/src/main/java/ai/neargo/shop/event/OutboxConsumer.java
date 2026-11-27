package ai.neargo.shop.event;

import java.util.Set;

import ai.neargo.shop.event.outbox.DeliveryResult;

/**
 * 领域事件消费者。实现类声明为 {@code @Component} 即自动接入投递（见 {@link OutboxDispatcher}）。
 *
 * <p><b>实现者必须自己保证幂等</b>：投递语义是 at-least-once，同一事件可能被投递多次
 * （投递器崩溃在「消费成功」与「标记已发送」之间是最常见的情况）。
 */
public interface OutboxConsumer {

    /** Explicit stable identity is mandatory when opting into unified delivery. */
    default String handlerId() { throw new IllegalStateException("Stable outbox handler ID required: " + getClass().getName()); }

    /** Target database, not the source event database. */
    default String targetSource() { return "PLATFORM"; }

    /**
     * 通知类消费者：事件提交后在后台收（{@link AfterCommitNotifications}），发件箱投递跳过它，丢一次可以接受。
     * 一个事件类型只有这类消费者时不落发件箱（ADR-067）。
     */
    default boolean afterCommitOnly() { return false; }

    /**
     * 这个消费者收哪些事件类型，用于 MQ 队列绑定（ADR-067）。空 = 全收，由 {@link #supports} 过滤。
     * 只需通知类消费者实现；必须与 {@link #supports} 一致。
     */
    default Set<String> eventTypes() { return Set.of(); }

    /**
     * 中间件订阅组（ADR-067）。同组的必达消费者合成一个保序订阅：一条队列、同一聚合按序，
     * 组内按 bean 顺序逐个调 —— 与进程内投递“一条事件依次调各消费者”的语义一致。
     * 几个消费者写同一份数据、彼此有先后依赖时（如同一 SKU 的建档与下架）必须同组，否则各自一条队列会乱序。
     */
    default String subscriptionGroup() { return handlerId(); }

    /** External workflows keep their own action ledger outside an enclosing JDBC transaction. */
    default boolean externalWorkflow() { return false; }

    boolean supports(String eventType);

    /** 消费。抛异常表示失败，事件会留在队列里重投。 */
    void consume(SysOutbox event);

    /** Unified delivery may expose a durable business outcome instead of swallowing failures. */
    default DeliveryResult deliver(SysOutbox event) {
        consume(event);
        return DeliveryResult.sent();
    }
}
