package ai.neargo.shop.event.rabbit;

import ai.neargo.shop.event.DeliveryContext;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import tools.jackson.databind.ObjectMapper;

/**
 * 一个通知类消费者的队列监听：还原信封、带着事件号调现有的 {@code consume}。
 * 抛出的异常交给容器拒绝并重投，超过投递上限进死信（ADR-067 决策 5）。
 */
final class RabbitNotificationListener implements MessageListener {

    private final OutboxConsumer consumer;
    private final ObjectMapper json;

    RabbitNotificationListener(OutboxConsumer consumer, ObjectMapper json) {
        this.consumer = consumer;
        this.json = json;
    }

    @Override
    public void onMessage(Message message) {
        SysOutbox envelope = EventMessages.fromMessage(message, json);
        if (envelope.getEventType() == null || !consumer.supports(envelope.getEventType())) {
            return;     // 全收绑定下别人的事件：确认掉即可
        }
        DeliveryContext.run(envelope.getEventNo(), () -> consumer.consume(envelope));
    }
}
