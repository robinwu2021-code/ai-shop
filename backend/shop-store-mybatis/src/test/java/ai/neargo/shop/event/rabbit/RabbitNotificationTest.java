package ai.neargo.shop.event.rabbit;

import ai.neargo.shop.event.DeliveryContext;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR-067 第 3 步：通知类事件经 RabbitMQ 收发（不连 broker 的部分）。 */
@DisplayName("RabbitMQ 通知传输")
class RabbitNotificationTest {

    private final ObjectMapper json = new ObjectMapper();

    private static SysOutbox envelope() {
        SysOutbox e = new SysOutbox();
        e.setEventNo("EV-1");
        e.setAggregateType("ORDER");
        e.setAggregateId("O-1");
        e.setEventType("ORDER_ARRIVED");
        e.setPayload("{\"userNo\":\"U1\"}");
        return e;
    }

    @Test
    @DisplayName("消息：路由键 = 事件类型，消息号 = 事件号，持久化，往返不丢字段")
    void messageRoundTrip() {
        Message m = EventMessages.toMessage(envelope(), json);
        assertThat(m.getMessageProperties().getMessageId()).isEqualTo("EV-1");
        assertThat(m.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        SysOutbox back = EventMessages.fromMessage(m, json);
        assertThat(back.getEventNo()).isEqualTo("EV-1");
        assertThat(back.getAggregateType()).isEqualTo("ORDER");
        assertThat(back.getAggregateId()).isEqualTo("O-1");
        assertThat(back.getEventType()).isEqualTo("ORDER_ARRIVED");
        assertThat(back.getPayload()).isEqualTo("{\"userNo\":\"U1\"}");
    }

    @Test
    @DisplayName("拓扑：每个消费者一条仲裁队列，带投递上限与死信；按事件类型绑定，未列类型就全收")
    void topologyPerConsumer() {
        var props = new RabbitEventsProperties();
        var declared = RabbitTopology.forConsumer("notification", Set.of("ORDER_ARRIVED", "REVIEW_CREATED"), props);
        Queue q = declared.getDeclarablesByType(Queue.class).stream()
                .filter(x -> x.getName().equals("shop.events.notification")).findFirst().orElseThrow();
        assertThat(q.getArguments()).containsEntry("x-queue-type", "quorum")
                .containsEntry("x-delivery-limit", props.getDeliveryLimit())
                .containsEntry("x-dead-letter-exchange", "shop.events.dlx");
        assertThat(declared.getDeclarablesByType(Queue.class)).extracting(Queue::getName)
                .contains("shop.events.notification.dlq");
        assertThat(declared.getDeclarablesByType(Binding.class))
                .filteredOn(b -> b.getExchange().equals("shop.events"))
                .extracting(Binding::getRoutingKey).containsExactlyInAnyOrder("ORDER_ARRIVED", "REVIEW_CREATED");

        var all = RabbitTopology.forConsumer("print-notify", Set.of(), props);
        assertThat(all.getDeclarablesByType(Binding.class))
                .filteredOn(b -> b.getExchange().equals("shop.events"))
                .extracting(Binding::getRoutingKey).containsExactly("#");
    }

    @Test
    @DisplayName("收到消息：带着事件号调消费者；不支持的类型直接确认；消费者抛错就抛出去让 MQ 重投")
    void listenerDeliversWithEventNo() {
        List<String> seen = new ArrayList<>();
        OutboxConsumer c = new OutboxConsumer() {
            @Override public boolean afterCommitOnly() { return true; }
            @Override public boolean supports(String t) { return "ORDER_ARRIVED".equals(t); }
            @Override public void consume(SysOutbox e) {
                if ("BOOM".equals(e.getAggregateId())) throw new IllegalStateException("down");
                seen.add(e.getAggregateId() + "@" + DeliveryContext.eventNo());
            }
        };
        var listener = new RabbitNotificationListener(c, json);
        listener.onMessage(EventMessages.toMessage(envelope(), json));
        assertThat(seen).containsExactly("O-1@EV-1");

        SysOutbox other = envelope();
        other.setEventType("SOMETHING_ELSE");
        listener.onMessage(EventMessages.toMessage(other, json));
        assertThat(seen).hasSize(1);

        SysOutbox boom = envelope();
        boom.setAggregateId("BOOM");
        assertThatThrownBy(() -> listener.onMessage(EventMessages.toMessage(boom, json)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(DeliveryContext.eventNo()).isNull();
    }

    @Test
    @DisplayName("发送：broker 暂时连不上就本地重试，重试用尽只记日志不抛")
    void transportRetriesThenGivesUp() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        when(template.invoke(any())).thenThrow(new AmqpConnectException(new RuntimeException("down")))
                .thenReturn(null);
        var transport = new RabbitNotificationTransport(template, json, 3, 0L, Runnable::run);
        transport.send(envelope());
        verify(template, times(2)).invoke(any());

        RabbitTemplate dead = mock(RabbitTemplate.class);
        when(dead.invoke(any())).thenThrow(new AmqpConnectException(new RuntimeException("down")));
        new RabbitNotificationTransport(dead, json, 3, 0L, Runnable::run).send(envelope());
        verify(dead, times(3)).invoke(any());
    }

    @Test
    @DisplayName("配置：默认 local 不建任何 MQ 组件；切 rabbitmq 漏配口令启动即失败")
    void configurationSwitch() {
        var runner = new ApplicationContextRunner().withUserConfiguration(RabbitEventsConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new);
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(RabbitNotificationTransport.class));
        runner.withPropertyValues("shop.events.transport=rabbitmq", "shop.events.rabbitmq.host=127.0.0.1",
                        "shop.events.rabbitmq.username=neargo_app")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("shop.events.rabbitmq.password"));
    }
}
