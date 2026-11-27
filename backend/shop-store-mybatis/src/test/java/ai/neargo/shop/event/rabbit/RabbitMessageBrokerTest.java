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
                .extracting(Binding::getRoutingKey).containsExactlyInAnyOrder("ORDER_ARRIVED", "REVIEW_CREATED");
        assertThat(RabbitTopology.listenQueues(
                new BrokerSubscription("notification", Set.of("X"), false), props)).containsExactly("shop.events.notification");
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
                .singleElement().satisfies(b -> {
                    assertThat(b.getDestination()).isEqualTo("shop.events.card-sale.hash");
                    assertThat(b.getRoutingKey()).isEqualTo("ORDER_PAID");
                });
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
