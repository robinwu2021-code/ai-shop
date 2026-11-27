package ai.neargo.shop.event.rabbit;

import ai.neargo.shop.event.broker.BrokerMessage;
import ai.neargo.shop.event.broker.BrokerSubscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 连真 broker（ADR-067 第 3–4 步）。开发机没有 RabbitMQ，默认跳过；在能连 RabbitMQ 的机器上：
 * <pre>
 * SHOP_RABBITMQ_IT_HOST=… SHOP_RABBITMQ_IT_PORT=… SHOP_RABBITMQ_IT_VHOST=… \
 * SHOP_RABBITMQ_IT_USERNAME=… SHOP_RABBITMQ_IT_PASSWORD=… mvn -pl shop-store-mybatis test -Dtest=RabbitMessageBrokerIT
 * </pre>
 * 保序订阅要求服务端启用 {@code rabbitmq_consistent_hash_exchange} 插件。用随机前缀，跑完删掉，不碰正式拓扑。
 */
@EnabledIfEnvironmentVariable(named = "SHOP_RABBITMQ_IT_HOST", matches = ".+")
@DisplayName("RabbitMQ 实现（真 broker）")
class RabbitMessageBrokerIT {

    private final String prefix = "it." + UUID.randomUUID().toString().substring(0, 8);
    private final ObjectMapper json = new ObjectMapper();
    private final RabbitEventsProperties props = props();
    private final CachingConnectionFactory factory = factory();
    private final RabbitMessageBroker broker = new RabbitMessageBroker(factory, props, json);

    private RabbitEventsProperties props() {
        var p = new RabbitEventsProperties();
        p.setHost(System.getenv("SHOP_RABBITMQ_IT_HOST"));
        p.setPort(Integer.parseInt(System.getenv().getOrDefault("SHOP_RABBITMQ_IT_PORT", "5672")));
        p.setVirtualHost(System.getenv().getOrDefault("SHOP_RABBITMQ_IT_VHOST", "/"));
        p.setUsername(System.getenv("SHOP_RABBITMQ_IT_USERNAME"));
        p.setPassword(System.getenv("SHOP_RABBITMQ_IT_PASSWORD"));
        p.setExchange(prefix + ".events");
        p.setDeadLetterExchange(prefix + ".dlx");
        p.setQueuePrefix(prefix);
        p.setDeliveryLimit(2);
        p.setPartitions(2);
        return p;
    }

    private CachingConnectionFactory factory() {
        var f = new CachingConnectionFactory(props.getHost(), props.getPort());
        f.setVirtualHost(props.getVirtualHost());
        f.setUsername(props.getUsername());
        f.setPassword(props.getPassword());
        f.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        f.setPublisherReturns(true);
        return f;
    }

    @AfterEach
    void cleanUp() {
        broker.stop();
        var admin = new RabbitAdmin(factory);
        for (String q : List.of("notify", "notify.dlq", "durable.p0", "durable.p1", "durable.dlq")) {
            admin.deleteQueue(prefix + "." + q);
        }
        admin.deleteExchange(prefix + ".durable.hash");
        admin.deleteExchange(props.getExchange());
        admin.deleteExchange(props.getDeadLetterExchange());
        factory.destroy();
    }

    @Test
    @DisplayName("不保序与保序订阅各收一份；同一聚合按顺序；一直失败的进死信；没人订阅的类型发布即失败")
    void endToEnd() throws Exception {
        List<String> notified = new CopyOnWriteArrayList<>();
        List<String> durable = new CopyOnWriteArrayList<>();
        AtomicInteger boom = new AtomicInteger();
        broker.subscribe(new BrokerSubscription("notify", Set.of("IT_EVENT"), false), m -> {
            if ("BOOM".equals(m.aggregateId())) { boom.incrementAndGet(); throw new IllegalStateException("fails"); }
            notified.add(m.eventNo());
        });
        broker.subscribe(new BrokerSubscription("durable", Set.of("IT_EVENT"), true), m -> durable.add(m.eventNo()));
        broker.start();

        for (int i = 1; i <= 5; i++) broker.publish(new BrokerMessage("EV-" + i, "IT", "A-1", "IT_EVENT", "{}"));
        broker.publish(new BrokerMessage("EV-BOOM", "IT", "BOOM", "IT_EVENT", "{}"));

        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && (durable.size() < 6 || dlq() < 1)) Thread.sleep(200);

        assertThat(notified).containsExactlyInAnyOrder("EV-1", "EV-2", "EV-3", "EV-4", "EV-5");
        assertThat(durable.subList(0, 6).stream().filter(e -> !e.equals("EV-BOOM")).toList())
                .as("同一聚合按发布顺序").containsExactly("EV-1", "EV-2", "EV-3", "EV-4", "EV-5");
        assertThat(boom.get()).isGreaterThanOrEqualTo(2);
        assertThat(dlq()).isEqualTo(1L);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                broker.publish(new BrokerMessage("EV-X", "IT", "A-2", "NOBODY_LISTENS", "{}")))
                .isInstanceOf(ai.neargo.shop.event.broker.BrokerException.class);
    }

    private long dlq() {
        var info = new RabbitAdmin(factory).getQueueInfo(prefix + ".notify.dlq");
        return info == null ? 0 : info.getMessageCount();
    }
}
