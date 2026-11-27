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

/** {@link BrokerMessage} 与 AMQP 消息互转。聚合号另放头 {@value #AGGREGATE_HEADER}，保序分区按它哈希。 */
final class EventMessages {

    static final String AGGREGATE_HEADER = "x-aggregate-id";

    private EventMessages() { }

    static Message toMessage(BrokerMessage e, ObjectMapper json) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("eventNo", e.eventNo());
        body.put("aggregateType", e.aggregateType());
        body.put("aggregateId", e.aggregateId());
        body.put("eventType", e.eventType());
        body.put("payload", e.payload());
        return MessageBuilder.withBody(json.writeValueAsBytes(body))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .setMessageId(e.eventNo())
                .setType(e.eventType())
                .setHeader(AGGREGATE_HEADER, e.aggregateId() == null ? e.eventNo() : e.aggregateId())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }

    static BrokerMessage fromMessage(Message m, ObjectMapper json) {
        JsonNode body = json.readTree(m.getBody());
        return new BrokerMessage(text(body, "eventNo"), text(body, "aggregateType"), text(body, "aggregateId"),
                text(body, "eventType"), text(body, "payload"));
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asString();
    }
}
