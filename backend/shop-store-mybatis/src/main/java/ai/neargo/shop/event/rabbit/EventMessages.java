package ai.neargo.shop.event.rabbit;

import ai.neargo.shop.event.SysOutbox;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** 事件信封与 AMQP 消息互转。消息体是信封的五个字段，消费端还原成 {@link SysOutbox} 交给现有消费者。 */
final class EventMessages {

    private EventMessages() { }

    static Message toMessage(SysOutbox e, ObjectMapper json) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("eventNo", e.getEventNo());
        body.put("aggregateType", e.getAggregateType());
        body.put("aggregateId", e.getAggregateId());
        body.put("eventType", e.getEventType());
        body.put("payload", e.getPayload());
        return MessageBuilder.withBody(json.writeValueAsBytes(body))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .setMessageId(e.getEventNo())
                .setType(e.getEventType())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }

    static SysOutbox fromMessage(Message m, ObjectMapper json) {
        JsonNode body = json.readTree(m.getBody());
        SysOutbox e = new SysOutbox();
        e.setEventNo(text(body, "eventNo"));
        e.setAggregateType(text(body, "aggregateType"));
        e.setAggregateId(text(body, "aggregateId"));
        e.setEventType(text(body, "eventType"));
        e.setPayload(text(body, "payload"));
        return e;
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asString();
    }
}
