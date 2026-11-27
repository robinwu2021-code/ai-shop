package ai.neargo.shop.event.broker;

import ai.neargo.shop.event.SysOutbox;

/**
 * 经消息中间件传递的一条事件。与具体中间件无关：RabbitMQ、别的 MQ、进程内实现都用它（ADR-067 决策 9）。
 *
 * @param eventNo     事件号，下游按它幂等
 * @param aggregateId 聚合号；保序订阅按它分区，同一聚合的事件按发出顺序处理
 */
public record BrokerMessage(String eventNo, String aggregateType, String aggregateId, String eventType,
                            String payload) {

    public static BrokerMessage of(SysOutbox e) {
        return new BrokerMessage(e.getEventNo(), e.getAggregateType(), e.getAggregateId(), e.getEventType(),
                e.getPayload());
    }

    /** 还原成现有消费者认识的信封。 */
    public SysOutbox toEnvelope() {
        SysOutbox e = new SysOutbox();
        e.setEventNo(eventNo);
        e.setAggregateType(aggregateType);
        e.setAggregateId(aggregateId);
        e.setEventType(eventType);
        e.setPayload(payload);
        return e;
    }
}
