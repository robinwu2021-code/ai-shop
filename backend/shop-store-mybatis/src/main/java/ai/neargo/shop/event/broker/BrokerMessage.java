package ai.neargo.shop.event.broker;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.outbox.OutboxEnvelope;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 经消息中间件传递的一条事件。与具体中间件无关：RabbitMQ、别的 MQ、进程内实现都用它（ADR-067 决策 9）。
 *
 * @param eventNo     事件号，下游按它幂等
 * @param orderingKey 排序键：保序订阅按它分区，同一键的消息按发出顺序处理（旧投递模式 = 聚合号，统一模式 = 流键）
 * @param target      目标订阅名；非空时只投给这一个订阅（统一模式按“事件 × 处理者”逐条投递），空 = 按事件类型投给所有订阅
 * @param headers     还原统一模式信封所需的其余字段（来源、来源事件号、版本、流内序号、发生时间），旧模式为空
 */
public record BrokerMessage(String eventNo, String aggregateType, String aggregateId, String eventType,
                            String payload, String orderingKey, String target, Map<String, String> headers) {

    public static final String SOURCE_SYSTEM = "sourceSystem";
    public static final String SOURCE_EVENT_NO = "sourceEventNo";
    public static final String SCHEMA_VERSION = "schemaVersion";
    public static final String STREAM_SEQUENCE = "streamSequence";
    public static final String OCCURRED_AT = "occurredAt";

    public BrokerMessage {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        if (orderingKey == null || orderingKey.isBlank()) {
            orderingKey = aggregateId == null || aggregateId.isBlank() ? eventNo : aggregateId;
        }
    }

    /** 旧投递模式：按事件类型投给所有订阅，按聚合号保序。 */
    public BrokerMessage(String eventNo, String aggregateType, String aggregateId, String eventType, String payload) {
        this(eventNo, aggregateType, aggregateId, eventType, payload, aggregateId, null, Map.of());
    }

    public static BrokerMessage of(SysOutbox e) {
        return new BrokerMessage(e.getEventNo(), e.getAggregateType(), e.getAggregateId(), e.getEventType(),
                e.getPayload());
    }

    /** 统一投递模式的一条“事件 × 处理者”投递：只投给这个处理者的订阅，按流键保序。 */
    public static BrokerMessage of(OutboxEnvelope e, String handlerId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(SOURCE_SYSTEM, e.sourceSystem());
        headers.put(SOURCE_EVENT_NO, e.sourceEventNo());
        headers.put(SCHEMA_VERSION, Integer.toString(e.schemaVersion()));
        headers.put(STREAM_SEQUENCE, Long.toString(e.streamSequence()));
        headers.put(OCCURRED_AT, e.occurredAt().toString());
        return new BrokerMessage(e.eventNo(), e.aggregateType(), e.aggregateId(), e.eventType(), e.payload(),
                e.streamKey(), handlerId, headers);
    }

    /** 还原成旧模式消费者认识的信封。 */
    public SysOutbox toEnvelope() {
        SysOutbox e = new SysOutbox();
        e.setEventNo(eventNo);
        e.setAggregateType(aggregateType);
        e.setAggregateId(aggregateId);
        e.setEventType(eventType);
        e.setPayload(payload);
        return e;
    }

    /** 还原成统一模式处理者认识的信封。 */
    public OutboxEnvelope toOutboxEnvelope() {
        return new OutboxEnvelope(eventNo, headers.get(SOURCE_SYSTEM), headers.get(SOURCE_EVENT_NO), aggregateType,
                aggregateId, eventType, Integer.parseInt(headers.getOrDefault(SCHEMA_VERSION, "1")), orderingKey,
                Long.parseLong(headers.getOrDefault(STREAM_SEQUENCE, "0")), payload,
                Instant.parse(headers.get(OCCURRED_AT)));
    }
}
