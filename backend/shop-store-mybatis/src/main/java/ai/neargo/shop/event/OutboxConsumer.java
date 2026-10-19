package ai.neargo.shop.event;

import ai.neargo.shop.event.outbox.DeliveryResult;

/**
 * 领域事件消费者。实现类声明为 {@code @Component} 即自动接入投递（见 {@link OutboxDispatcher}）。
 *
 * <p><b>实现者必须自己保证幂等</b>：投递语义是 at-least-once，同一事件可能被投递多次
 * （投递器崩溃在「消费成功」与「标记已发送」之间是最常见的情况）。
 */
public interface OutboxConsumer {

    /** Explicit stable identity is mandatory when opting into unified delivery. */
    default String handlerId() { throw new IllegalStateException("Stable outbox handler ID required"); }

    /** Target database, not the source event database. */
    default String targetSource() { return "PLATFORM"; }

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
