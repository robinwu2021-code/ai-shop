package ai.neargo.shop.event.rabbit;

import ai.neargo.shop.event.broker.BrokerSubscription;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * RabbitMQ 拓扑（ADR-067 决策 3）。一个 topic 事件交换机，路由键 = 事件类型；每个订阅：
 * <ul>
 *   <li>不保序：一条仲裁队列 {@code <前缀>.<订阅名>}；</li>
 *   <li>保序：一个一致性哈希交换机（按头 {@code x-aggregate-id} 哈希，需服务端插件
 *       {@code rabbitmq_consistent_hash_exchange}）+ N 条分区队列 {@code <前缀>.<订阅名>.p<i>}，每条单活消费者。</li>
 * </ul>
 * 都带投递上限，超限进该订阅自己的死信队列 {@code <前缀>.<订阅名>.dlq}。
 *
 * <p>广播订阅：每个进程实例一条非持久、最后一个消费者断开即删的经典队列 {@code <前缀>.<订阅名>.<实例号>}，
 * 不设投递上限与死信。
 */
final class RabbitTopology {

    /** 本进程实例号：广播订阅的队列名带上它，各实例各一条。 */
    static final String INSTANCE = java.util.UUID.randomUUID().toString().substring(0, 8);

    private RabbitTopology() { }

    static String broadcastQueue(String subscription, RabbitEventsProperties p) {
        return queueName(subscription, p) + "." + INSTANCE;
    }

    static String queueName(String subscription, RabbitEventsProperties p) {
        return p.getQueuePrefix() + "." + subscription;
    }

    static String partitionQueue(String subscription, int i, RabbitEventsProperties p) {
        return queueName(subscription, p) + ".p" + i;
    }

    /** 订阅要监听的队列名。 */
    static List<String> listenQueues(BrokerSubscription s, RabbitEventsProperties p) {
        if (s.broadcast()) return List.of(broadcastQueue(s.name(), p));
        if (!s.ordered()) return List.of(queueName(s.name(), p));
        List<String> names = new ArrayList<>();
        for (int i = 0; i < partitions(p); i++) names.add(partitionQueue(s.name(), i, p));
        return names;
    }

    static Declarables forSubscription(BrokerSubscription s, RabbitEventsProperties p) {
        TopicExchange events = new TopicExchange(p.getExchange(), true, false);
        if (s.broadcast()) {
            String name = broadcastQueue(s.name(), p);
            List<Declarable> all = new ArrayList<>(List.of(events, QueueBuilder.nonDurable(name).autoDelete().build()));
            for (String t : new TreeSet<>(s.eventTypes())) {
                all.add(new Binding(name, Binding.DestinationType.QUEUE, events.getName(), t, null));
            }
            return new Declarables(all);
        }
        DirectExchange dlx = new DirectExchange(p.getDeadLetterExchange(), true, false);
        String base = queueName(s.name(), p);
        Queue dlq = QueueBuilder.durable(base + ".dlq").quorum().build();
        List<Declarable> all = new ArrayList<>(List.of(events, dlx, dlq,
                BindingBuilder.bind(dlq).to(dlx).with(s.name())));
        TreeSet<String> types = new TreeSet<>(s.eventTypes());
        // 定向投递（统一模式按“事件 × 处理者”）：路由键 @订阅名，只到这一个订阅
        String direct = EventMessages.TARGET_PREFIX + s.name();
        if (!s.ordered()) {
            all.add(workQueue(base, s.name(), p, false));
            for (String t : types) {
                all.add(new Binding(base, Binding.DestinationType.QUEUE, events.getName(), t, null));
            }
            all.add(new Binding(base, Binding.DestinationType.QUEUE, events.getName(), direct, null));
            return new Declarables(all);
        }
        CustomExchange hash = new CustomExchange(base + ".hash", "x-consistent-hash", true, false,
                Map.of("hash-header", EventMessages.AGGREGATE_HEADER));
        all.add(hash);
        for (String t : types) {
            all.add(new Binding(hash.getName(), Binding.DestinationType.EXCHANGE, events.getName(), t, null));
        }
        all.add(new Binding(hash.getName(), Binding.DestinationType.EXCHANGE, events.getName(), direct, null));
        for (int i = 0; i < partitions(p); i++) {
            String name = partitionQueue(s.name(), i, p);
            all.add(workQueue(name, s.name(), p, true));
            // 一致性哈希交换机里路由键是权重
            all.add(new Binding(name, Binding.DestinationType.QUEUE, hash.getName(), "1", null));
        }
        return new Declarables(all);
    }

    private static Queue workQueue(String name, String subscription, RabbitEventsProperties p, boolean single) {
        QueueBuilder b = QueueBuilder.durable(name).quorum()
                .deliveryLimit(p.getDeliveryLimit())
                .deadLetterExchange(p.getDeadLetterExchange())
                .deadLetterRoutingKey(subscription);
        if (single) b.singleActiveConsumer();
        return b.build();
    }

    private static int partitions(RabbitEventsProperties p) {
        return Math.max(1, p.getPartitions());
    }
}
