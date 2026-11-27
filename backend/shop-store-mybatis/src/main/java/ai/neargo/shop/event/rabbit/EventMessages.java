package ai.neargo.shop.event.rabbit;

import ai.neargo.shop.event.broker.BrokerMessage;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link BrokerMessage} 与 AMQP 消息互转。排序键另放头 {@value #AGGREGATE_HEADER}，保序分区按它哈希；
 * 有目标订阅时路由键是 {@code @订阅名}，否则是事件类型。
 */
final class EventMessages {

    /** 排序键头（历史名沿用 x-aggregate-id：旧模式的排序键就是聚合号）。 */
    static final String AGGREGATE_HEADER = "x-aggregate-id";
    static final String TARGET_PREFIX = "@";

    static String routingKey(BrokerMessage e) {
        return e.target() == null ? e.eventType() : TARGET_PREFIX + e.target();
    }

    private EventMessages() { }

    static Message toMessage(BrokerMessage e, ObjectMapper json) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("eventNo", e.eventNo());
        body.put("aggregateType", e.aggregateType());
        body.put("aggregateId", e.aggregateId());
        body.put("eventType", e.eventType());
        body.put("payload", e.payload());
        body.put("orderingKey", e.orderingKey());
        if (e.target() != null) body.put("target", e.target());
        e.headers().forEach((k, v) -> body.put("h." + k, v));
        return MessageBuilder.withBody(json.writeValueAsBytes(body))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .setMessageId(e.eventNo())
                .setType(e.eventType())
                .setHeader(AGGREGATE_HEADER, e.orderingKey())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }

    static BrokerMessage fromMessage(Message m, ObjectMapper json) {
        JsonNode body = json.readTree(m.getBody());
        Map<String, String> headers = new LinkedHashMap<>();
        body.properties().forEach(e -> {
            if (e.getKey().startsWith("h.")) headers.put(e.getKey().substring(2), e.getValue().asString());
        });
        return new BrokerMessage(text(body, "eventNo"), text(body, "aggregateType"), text(body, "aggregateId"),
                text(body, "eventType"), text(body, "payload"), text(body, "orderingKey"), text(body, "target"),
                headers);
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asString();
    }
}
