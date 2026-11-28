package ai.neargo.shop.event.broker;

import java.util.Set;

/**
 * 一个订阅：一个消费者（名字 = 现有 {@code handlerId}）收哪些事件类型。
 *
 * @param ordered   true = 同一聚合号的事件按发出顺序、逐条处理（中间件按聚合号分区实现）
 * @param broadcast true = 广播：<b>每个进程实例各收一份</b>（如通知连在本实例上的屏刷新）。
 *                  不持久、不重投、不进死信——实例不在线时发出的消息它收不到，处理失败只丢弃；只用于丢了也无妨的信号
 */
public record BrokerSubscription(String name, Set<String> eventTypes, boolean ordered, boolean broadcast) {

    public BrokerSubscription {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("subscription name required");
        if (eventTypes == null || eventTypes.isEmpty())
            throw new IllegalArgumentException("subscription " + name + " must list its event types");
        if (ordered && broadcast) throw new IllegalArgumentException("subscription " + name + " cannot be ordered broadcast");
        eventTypes = Set.copyOf(eventTypes);
    }

    /** 普通订阅：同名订阅的多个实例共用一份，一条消息只投给其中一个。 */
    public BrokerSubscription(String name, Set<String> eventTypes, boolean ordered) {
        this(name, eventTypes, ordered, false);
    }

    /** 广播订阅：每个进程实例各收一份，见 {@link #broadcast()}。 */
    public static BrokerSubscription broadcast(String name, Set<String> eventTypes) {
        return new BrokerSubscription(name, eventTypes, false, true);
    }
}
