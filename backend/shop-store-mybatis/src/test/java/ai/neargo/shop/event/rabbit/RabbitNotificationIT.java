package ai.neargo.shop.event.rabbit;

import ai.neargo.shop.event.DeliveryContext;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 连真 broker 的收发（ADR-067 第 3 步）。开发机没有 RabbitMQ，所以默认跳过；在能连 RabbitMQ 的机器上：
 * <pre>
 * SHOP_RABBITMQ_IT_HOST=… SHOP_RABBITMQ_IT_PORT=… SHOP_RABBITMQ_IT_VHOST=… \
 * SHOP_RABBITMQ_IT_USERNAME=… SHOP_RABBITMQ_IT_PASSWORD=… mvn -pl shop-store-mybatis test -Dtest=RabbitNotificationIT
 * </pre>
 * 用随机前缀的交换机与队列，跑完删掉，不碰正式拓扑。
 */
@EnabledIfEnvironmentVariable(named = "SHOP_RABBITMQ_IT_HOST", matches = ".+")
@DisplayName("RabbitMQ 通知收发（真 broker）")
class RabbitNotificationIT {

    private final String prefix = "it." + UUID.randomUUID().toString().substring(0, 8);
    private final ObjectMapper json = new ObjectMapper();
    private final RabbitEventsProperties props = props();
    private final CachingConnectionFactory factory = factory();
    private RabbitNotificationRuntime runtime;

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
        return p;
    }

    private CachingConnectionFactory factory() {
        var f = new CachingConnectionFactory(props.getHost(), props.getPort());
        f.setVirtualHost(props.getVirtualHost());
        f.setUsername(props.getUsername());
        f.setPassword(props.getPassword());
        f.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        return f;
    }

    @AfterEach
    void cleanUp() {
        if (runtime != null) runtime.stop();
        var admin = new RabbitAdmin(factory);
        for (String q : List.of(prefix + ".it-notify", prefix + ".it-notify.dlq")) admin.deleteQueue(q);
        admin.deleteExchange(props.getExchange());
        admin.deleteExchange(props.getDeadLetterExchange());
        factory.destroy();
    }

    @Test
    @DisplayName("提交后发出 → 消费者带着事件号收到；一直失败的超过投递上限进死信队列")
    void roundTripAndDeadLetter() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        AtomicInteger boomAttempts = new AtomicInteger();
        OutboxConsumer consumer = new OutboxConsumer() {
            @Override public String handlerId() { return "it-notify"; }
            @Override public boolean afterCommitOnly() { return true; }
            @Override public Set<String> eventTypes() { return Set.of("IT_EVENT"); }
            @Override public boolean supports(String t) { return "IT_EVENT".equals(t); }
            @Override public void consume(SysOutbox e) {
                if ("BOOM".equals(e.getAggregateId())) {
                    boomAttempts.incrementAndGet();
                    throw new IllegalStateException("always fails");
                }
                seen.add(e.getAggregateId() + "@" + DeliveryContext.eventNo());
            }
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<OutboxConsumer> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> Stream.of(consumer));
        runtime = new RabbitNotificationRuntime(factory, provider, props, json);
        runtime.start();

        var template = new RabbitTemplate(factory);
        template.setExchange(props.getExchange());
        var transport = new RabbitNotificationTransport(template, json, 3, 100, Runnable::run);
        transport.send(envelope("EV-IT-1", "OK"));
        transport.send(envelope("EV-IT-2", "BOOM"));

        long deadline = System.currentTimeMillis() + 15_000;
        Long dlq = null;
        while (System.currentTimeMillis() < deadline) {
            var info = new RabbitAdmin(factory).getQueueInfo(prefix + ".it-notify.dlq");
            dlq = info == null ? null : (long) info.getMessageCount();
            if (!seen.isEmpty() && dlq != null && dlq > 0) break;
            Thread.sleep(200);
        }
        assertThat(seen).containsExactly("OK@EV-IT-1");
        assertThat(boomAttempts.get()).isGreaterThanOrEqualTo(2);
        assertThat(dlq).isEqualTo(1L);
    }

    private static SysOutbox envelope(String eventNo, String aggregateId) {
        SysOutbox e = new SysOutbox();
        e.setEventNo(eventNo);
        e.setAggregateType("IT");
        e.setAggregateId(aggregateId);
        e.setEventType("IT_EVENT");
        e.setPayload("{}");
        return e;
    }
}
