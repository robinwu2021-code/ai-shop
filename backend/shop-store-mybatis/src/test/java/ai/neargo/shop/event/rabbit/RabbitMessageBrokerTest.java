package ai.neargo.shop.event.rabbit;

import ai.neargo.shop.event.broker.BrokerMessage;
import ai.neargo.shop.event.broker.BrokerSubscription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RabbitMQ 实现里不需要 broker 的部分（ADR-067）。 */
@DisplayName("RabbitMQ 实现")
class RabbitMessageBrokerTest {

    private final ObjectMapper json = new ObjectMapper();
    private final RabbitEventsProperties props = new RabbitEventsProperties();

    @Test
    @DisplayName("消息：消息号 = 事件号、持久化、聚合号进哈希头，往返不丢字段")
    void messageRoundTrip() {
        var original = new BrokerMessage("EV-1", "ORDER", "O-1", "ORDER_ARRIVED", "{\"userNo\":\"U1\"}");
        Message m = EventMessages.toMessage(original, json);
        assertThat(m.getMessageProperties().getMessageId()).isEqualTo("EV-1");
        assertThat(m.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat((String) m.getMessageProperties().getHeader(EventMessages.AGGREGATE_HEADER)).isEqualTo("O-1");
        assertThat(EventMessages.fromMessage(m, json)).isEqualTo(original);
    }

    @Test
    @DisplayName("统一模式的定向消息：路由键 @处理者，排序键用流键，信封字段往返不丢")
    void targetedMessageRoundTrip() {
        var envelope = new ai.neargo.shop.event.outbox.OutboxEnvelope("EV-9", "PLATFORM", "EV-9", "DINING", "C-1",
                "DINING_ORDER_ACCEPTED", 2, "STORE:S1:CHECK:C-1", 7, "{}", java.time.Instant.parse("2026-11-27T00:00:00Z"));
        var message = BrokerMessage.of(envelope, "food-kitchen-accepted-v1");
        assertThat(EventMessages.routingKey(message)).isEqualTo("@food-kitchen-accepted-v1");
        Message m = EventMessages.toMessage(message, json);
        assertThat((String) m.getMessageProperties().getHeader(EventMessages.AGGREGATE_HEADER))
                .isEqualTo("STORE:S1:CHECK:C-1");
        var back = EventMessages.fromMessage(m, json);
        assertThat(back).isEqualTo(message);
        assertThat(back.toOutboxEnvelope()).isEqualTo(envelope);
    }

    @Test
    @DisplayName("不保序订阅：一条仲裁队列，投递上限 + 死信，按事件类型绑定")
    void plainTopology() {
        var d = RabbitTopology.forSubscription(
                new BrokerSubscription("notification", Set.of("ORDER_ARRIVED", "REVIEW_CREATED"), false), props);
        Queue q = d.getDeclarablesByType(Queue.class).stream()
                .filter(x -> x.getName().equals("shop.events.notification")).findFirst().orElseThrow();
        assertThat(q.getArguments()).containsEntry("x-queue-type", "quorum")
                .containsEntry("x-delivery-limit", props.getDeliveryLimit())
                .containsEntry("x-dead-letter-exchange", "shop.events.dlx")
                .doesNotContainKey("x-single-active-consumer");
        assertThat(d.getDeclarablesByType(Binding.class)).filteredOn(b -> b.getExchange().equals("shop.events"))
                .extracting(Binding::getRoutingKey)
                .containsExactlyInAnyOrder("ORDER_ARRIVED", "REVIEW_CREATED", "@notification");
        assertThat(RabbitTopology.listenQueues(
                new BrokerSubscription("notification", Set.of("X"), false), props)).containsExactly("shop.events.notification");
    }

    @Test
    @DisplayName("广播订阅：每个实例一条非持久、自动删除的队列，没有投递上限与死信")
    void broadcastTopology() {
        var s = BrokerSubscription.broadcast("food-device-refresh", Set.of("FOOD_DEVICE_REFRESH"));
        var d = RabbitTopology.forSubscription(s, props);
        String name = "shop.events.food-device-refresh." + RabbitTopology.INSTANCE;
        assertThat(d.getDeclarablesByType(Queue.class)).singleElement().satisfies(q -> {
            assertThat(q.getName()).isEqualTo(name);
            assertThat(q.isDurable()).isFalse();
            assertThat(q.isAutoDelete()).isTrue();
            assertThat(q.getArguments()).doesNotContainKeys("x-queue-type", "x-dead-letter-exchange", "x-delivery-limit");
        });
        assertThat(d.getDeclarablesByType(Binding.class)).singleElement().satisfies(b -> {
            assertThat(b.getExchange()).isEqualTo("shop.events");
            assertThat(b.getRoutingKey()).isEqualTo("FOOD_DEVICE_REFRESH");
            assertThat(b.getDestination()).isEqualTo(name);
        });
        assertThat(RabbitTopology.listenQueues(s, props)).containsExactly(name);
        assertThatThrownBy(() -> new BrokerSubscription("x", Set.of("X"), true, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("保序订阅：按聚合号哈希分到 N 条分区队列，每条单活消费者")
    void orderedTopology() {
        props.setPartitions(3);
        var s = new BrokerSubscription("card-sale", Set.of("ORDER_PAID"), true);
        var d = RabbitTopology.forSubscription(s, props);
        Exchange hash = d.getDeclarablesByType(Exchange.class).stream()
                .filter(e -> e.getName().equals("shop.events.card-sale.hash")).findFirst().orElseThrow();
        assertThat(hash.getType()).isEqualTo("x-consistent-hash");
        assertThat(hash.getArguments()).containsEntry("hash-header", EventMessages.AGGREGATE_HEADER);
        assertThat(d.getDeclarablesByType(Binding.class)).filteredOn(b -> b.getExchange().equals("shop.events"))
                .allSatisfy(b -> assertThat(b.getDestination()).isEqualTo("shop.events.card-sale.hash"))
                .extracting(Binding::getRoutingKey).containsExactlyInAnyOrder("ORDER_PAID", "@card-sale");
        assertThat(RabbitTopology.listenQueues(s, props))
                .containsExactly("shop.events.card-sale.p0", "shop.events.card-sale.p1", "shop.events.card-sale.p2");
        assertThat(d.getDeclarablesByType(Queue.class)).filteredOn(q -> q.getName().contains(".p"))
                .allSatisfy(q -> assertThat(q.getArguments()).containsEntry("x-single-active-consumer", true));
    }

    @Test
    @DisplayName("配置：默认 local 不建实现；切 rabbitmq 漏配口令启动即失败")
    void configurationSwitch() {
        var runner = new ApplicationContextRunner().withUserConfiguration(RabbitEventsConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new);
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(RabbitMessageBroker.class));
        runner.withPropertyValues("shop.events.transport=rabbitmq", "shop.events.rabbitmq.host=127.0.0.1",
                        "shop.events.rabbitmq.username=neargo_app")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("shop.events.rabbitmq.password"));
    }
}
