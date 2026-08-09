package ai.neargo.shop.event;

/**
 * 领域事件消费者。实现类声明为 {@code @Component} 即自动接入投递（见 {@link OutboxDispatcher}）。
 *
 * <p><b>实现者必须自己保证幂等</b>：投递语义是 at-least-once，同一事件可能被投递多次
 * （投递器崩溃在「消费成功」与「标记已发送」之间是最常见的情况）。
 */
public interface OutboxConsumer {

    boolean supports(String eventType);

    /** 消费。抛异常表示失败，事件会留在队列里重投。 */
    void consume(SysOutbox event);
}
