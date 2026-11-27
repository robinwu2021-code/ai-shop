package ai.neargo.shop.event;

import ai.neargo.shop.event.broker.BrokerMessage;
import ai.neargo.shop.event.broker.BrokerProperties;
import ai.neargo.shop.event.broker.MessageBroker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发布入口只有 {@code publish}：按订阅者决定走法（ADR-067 决策 6）。
 * 通知类消费者提交后收；有必达消费者才落发件箱；没有任何消费者的照旧落库（存档语义不变）。
 */
@DisplayName("统一发布入口按订阅者决定走法")
class OutboxEventBusRoutingTest {

    private record Evt(String type) implements DomainEvent {
        @Override public String aggregateType() { return "T"; }
        @Override public String aggregateId() { return "A1"; }
        @Override public String eventType() { return type; }
    }

    private static OutboxConsumer consumer(boolean afterCommitOnly, String... types) {
        return new OutboxConsumer() {
            @Override public boolean afterCommitOnly() { return afterCommitOnly; }
            @Override public boolean supports(String t) { return Set.of(types).contains(t); }
            @Override public void consume(SysOutbox e) { }
        };
    }

    private final SysOutboxMapper mapper = mock(SysOutboxMapper.class);
    private final AfterCommitNotifications notifications = mock(AfterCommitNotifications.class);

    private OutboxEventBus bus(OutboxConsumer... consumers) {
        when(mapper.insert(any(SysOutbox.class))).thenAnswer(inv -> {
            ((SysOutbox) inv.getArgument(0)).setId(1L);
            return 1;
        });
        @SuppressWarnings("unchecked")
        ObjectProvider<OutboxPump> pumps = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OutboxConsumer> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> Stream.of(consumers));
        OutboxEventBus bus = new OutboxEventBus(mapper, new ObjectMapper(), pumps);
        bus.setNotifications(notifications);
        bus.setConsumers(provider);
        return bus;
    }

    @Test
    @DisplayName("只有通知类消费者：提交后通知，不落发件箱")
    void notifyOnlySkipsOutbox() {
        bus(consumer(true, "N"), consumer(false, "D")).publish(new Evt("N"));
        verify(notifications).publish(any(), any());
        verify(mapper, never()).insert(any(SysOutbox.class));
    }

    @Test
    @DisplayName("通知 + 必达：两边都走")
    void mixedGoesBothWays() {
        bus(consumer(true, "M"), consumer(false, "M")).publish(new Evt("M"));
        verify(notifications).publish(any(), any());
        verify(mapper).insert(any(SysOutbox.class));
    }

    @Test
    @DisplayName("只有必达消费者：只落发件箱")
    void durableOnlyGoesToOutbox() {
        bus(consumer(true, "N"), consumer(false, "D")).publish(new Evt("D"));
        verify(notifications, never()).publish(any(), any());
        verify(mapper).insert(any(SysOutbox.class));
    }

    @Test
    @DisplayName("没有任何消费者：不落库（R10.1，只存档行无人读）")
    void noConsumerIsNotPersisted() {
        bus(consumer(true, "N")).publish(new Evt("X"));
        verify(notifications, never()).publish(any(), any());
        verify(mapper, never()).insert(any(SysOutbox.class));
    }

    @Test
    @DisplayName("本进程没有消费者但在中间件名单里（别的进程订阅）：照样落库")
    void relayedTypeWithoutLocalConsumerIsPersisted() {
        var b = bus(consumer(true, "N"));
        var props = new ai.neargo.shop.event.broker.BrokerProperties();
        props.setOutboxEventTypes(Set.of("X"));
        b.setBrokerProperties(props);
        b.publish(new Evt("X"));
        verify(mapper).insert(any(SysOutbox.class));
    }

    @Test
    @DisplayName("统一模式下有登记的投递目标（另一个进程执行的处理者）：照样交给统一发件箱")
    void unifiedProducerTargetIsPersisted() {
        var b = bus(consumer(true, "N"));
        var facade = mock(ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade.class);
        when(facade.enabled("PLATFORM")).thenReturn(true);
        when(facade.hasDelivery("PLATFORM", "X")).thenReturn(true);
        b.setUnified(facade);
        b.publish(new Evt("X"));
        verify(facade).publish(org.mockito.ArgumentMatchers.eq("PLATFORM"), any(DomainEvent.class));
    }

    @Test
    @DisplayName("外部事件号发布同样按订阅者走，并把事件号带给通知")
    void externalEventNoIsRoutedToo() {
        bus(consumer(true, "N")).publish("EXT-1", new Evt("N"));
        verify(notifications).publish(org.mockito.ArgumentMatchers.eq("EXT-1"), any());
        verify(mapper, never()).insertIfAbsent(any(SysOutbox.class));
    }

    @Test
    @DisplayName("名单里的发件箱事件改由中间件投递：中间件确认才标已发，不再进程内调消费者；没确认就退避重投")
    void relayedTypesGoThroughBroker() {
        List<String> called = new java.util.ArrayList<>();
        OutboxConsumer durable = new OutboxConsumer() {
            @Override public boolean supports(String t) { return true; }
            @Override public void consume(SysOutbox e) { called.add(e.getEventType()); }
        };
        var dispatcher = new OutboxDispatcher(mapper, List.of(durable),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        var broker = mock(ai.neargo.shop.event.broker.MessageBroker.class);
        var props = new ai.neargo.shop.event.broker.BrokerProperties();
        props.setOutboxEventTypes(Set.of("ORDER_PAID"));
        dispatcher.setBroker(broker);
        dispatcher.setBrokerProperties(props);

        SysOutbox paid = row(1L, "ORDER_PAID");
        when(mapper.selectById(1L)).thenReturn(paid);
        assertThat(dispatcher.dispatchOne(1L).sent()).isTrue();
        verify(broker).publish(ai.neargo.shop.event.broker.BrokerMessage.of(paid));
        assertThat(called).isEmpty();
        assertThat(paid.getStatus()).isEqualTo(SysOutbox.SENT);

        SysOutbox other = row(2L, "SKU_UPSERTED");
        when(mapper.selectById(2L)).thenReturn(other);
        assertThat(dispatcher.dispatchOne(2L).sent()).isTrue();
        assertThat(called).containsExactly("SKU_UPSERTED");

        SysOutbox down = row(3L, "ORDER_PAID");
        when(mapper.selectById(3L)).thenReturn(down);
        org.mockito.Mockito.doThrow(new ai.neargo.shop.event.broker.BrokerException("down"))
                .when(broker).publish(ai.neargo.shop.event.broker.BrokerMessage.of(down));
        var outcome = dispatcher.dispatchOne(3L);
        assertThat(outcome.sent()).isFalse();
        assertThat(outcome.retryAfter()).isNotNull();
        assertThat(down.getStatus()).isEqualTo(SysOutbox.PENDING);
    }

    @Test
    @DisplayName("转发源头已持久化的事件：类型在名单里就直接发中间件、保留原事件号，不再落本库发件箱")
    void forwardRelayedTypeGoesStraightToBroker() {
        OutboxEventBus bus = bus(consumer(false, "D"));
        MessageBroker broker = mock(MessageBroker.class);
        BrokerProperties props = new BrokerProperties();
        props.setOutboxEventTypes(Set.of("D"));
        bus.setBroker(broker);
        bus.setBrokerProperties(props);

        bus.forward("PAY-1", new Evt("D"));

        verify(broker).publish(new BrokerMessage("PAY-1", "T", "A1", "D", "{\"type\":\"D\"}"));
        verify(mapper, never()).insertIfAbsent(any(SysOutbox.class));
    }

    @Test
    @DisplayName("转发不在名单里的事件：照外部事件号发布落发件箱，事件号不变")
    void forwardOtherTypeFallsBackToOutbox() {
        when(mapper.insertIfAbsent(any(SysOutbox.class))).thenReturn(1);
        OutboxEventBus bus = bus(consumer(false, "D"));
        bus.setBroker(mock(MessageBroker.class));
        bus.setBrokerProperties(new BrokerProperties());

        bus.forward("PAY-2", new Evt("D"));

        verify(mapper).insertIfAbsent(argThat(r -> "PAY-2".equals(r.getEventNo())));
    }

    private static SysOutbox row(long id, String type) {
        SysOutbox e = new SysOutbox();
        e.setId(id);
        e.setEventNo("EV-" + id);
        e.setAggregateType("ORDER");
        e.setAggregateId("O-" + id);
        e.setEventType(type);
        e.setPayload("{}");
        e.setStatus(SysOutbox.PENDING);
        e.setRetryCount(0);
        return e;
    }

    @Test
    @DisplayName("发件箱投递跳过通知类消费者")
    void dispatcherSkipsAfterCommitConsumers() {
        OutboxConsumer notify = consumer(true, "M");
        OutboxConsumer durable = consumer(false, "M");
        var dispatcher = new OutboxDispatcher(mapper, List.of(notify, durable),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        assertThat(dispatcher.deliveryConsumers()).containsExactly(durable);
    }
}
