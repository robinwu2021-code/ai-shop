package ai.neargo.shop.event.broker;

import java.util.Set;

/**
 * 一个订阅：一个消费者（名字 = 现有 {@code handlerId}）收哪些事件类型。
 *
 * @param ordered true = 同一聚合号的事件按发出顺序、逐条处理（中间件按聚合号分区实现）
 */
public record BrokerSubscription(String name, Set<String> eventTypes, boolean ordered) {

    public BrokerSubscription {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("subscription name required");
        if (eventTypes == null || eventTypes.isEmpty())
            throw new IllegalArgumentException("subscription " + name + " must list its event types");
        eventTypes = Set.copyOf(eventTypes);
    }
}
