package ai.neargo.shop.event.rabbit;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 拓扑（ADR-067 决策 3）：一个 topic 事件交换机、路由键 = 事件类型；每个消费者一条仲裁队列，
 * 超过投递上限进它自己的死信队列。
 */
final class RabbitTopology {

    private RabbitTopology() { }

    static TopicExchange exchange(RabbitEventsProperties p) {
        return new TopicExchange(p.getExchange(), true, false);
    }

    static DirectExchange deadLetterExchange(RabbitEventsProperties p) {
        return new DirectExchange(p.getDeadLetterExchange(), true, false);
    }

    static String queueName(String handlerId, RabbitEventsProperties p) {
        return p.getQueuePrefix() + "." + handlerId;
    }

    /**
     * @param eventTypes 这个消费者收的事件类型；空 = 全收（{@code #}），由监听器按 {@code supports} 过滤
     */
    static Declarables forConsumer(String handlerId, Set<String> eventTypes, RabbitEventsProperties p) {
        TopicExchange exchange = exchange(p);
        DirectExchange dlx = deadLetterExchange(p);
        String name = queueName(handlerId, p);
        Queue queue = QueueBuilder.durable(name).quorum()
                .deliveryLimit(p.getDeliveryLimit())
                .deadLetterExchange(dlx.getName())
                .deadLetterRoutingKey(handlerId)
                .build();
        Queue dlq = QueueBuilder.durable(name + ".dlq").quorum().build();
        List<Declarable> all = new ArrayList<>(List.of(exchange, dlx, queue, dlq,
                BindingBuilder.bind(dlq).to(dlx).with(handlerId)));
        Set<String> keys = eventTypes == null || eventTypes.isEmpty() ? Set.of("#") : new TreeSet<>(eventTypes);
        for (String key : keys) {
            all.add(new Binding(name, Binding.DestinationType.QUEUE, exchange.getName(), key, null));
        }
        return new Declarables(all);
    }
}
