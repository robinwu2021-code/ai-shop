package ai.neargo.inventory.domain.service;

/**
 * 领域事件的出口。**本域只负责把事件写进出站表，不负责送到哪去。**
 *
 * <p>实现由装配方提供（嵌入平台时转成平台事件，独立交付时可以是 webhook 或 MQ）。
 * 一个都没有时，{@link ai.neargo.inventory.domain.job.InvOutboxDispatchJob} **不会把行标成已发**
 * —— 事件会看得见地堆着，而不是被静默丢掉。
 * 「没有订阅者就当发过了」是这类表最常见的坏法：等有人来接的时候，前面几个月的事件已经没了。
 */
public interface InventoryEventSink {

    /**
     * @return 送达了返回 true；返回 false 或抛异常都会让这一行留在出站表里重试
     */
    boolean deliver(String eventNo, String ownerId, String eventType, String payload);

    /** Preserve source fact time while retaining compatibility with existing sink implementations. */
    default boolean deliver(String eventNo, String ownerId, String eventType, String payload,
                            java.time.Instant occurredAt) {
        return deliver(eventNo, ownerId, eventType, payload);
    }

    /**
     * 宿主自己可靠地接手事件（ADR-067 决策 10，R10.3）：为 true 时过账不写 {@code inv_outbox}，
     * 库存事务提交后直接调 {@link #deliver(String, String, String, String, java.time.Instant)}；回滚不调。
     * 提交后交付失败只记日志，事件丢失由宿主的兜底作业补。默认 false：独立部署照旧写本库出站表。
     */
    default boolean takesOverAfterCommit() {
        return false;
    }
}
