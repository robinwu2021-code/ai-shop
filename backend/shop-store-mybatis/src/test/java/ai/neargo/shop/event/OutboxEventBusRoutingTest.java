package ai.neargo.shop.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    @DisplayName("没有任何消费者：照旧落库，存档语义不变")
    void noConsumerStillPersists() {
        bus(consumer(true, "N")).publish(new Evt("X"));
        verify(notifications, never()).publish(any(), any());
        verify(mapper).insert(any(SysOutbox.class));
    }

    @Test
    @DisplayName("外部事件号发布同样按订阅者走，并把事件号带给通知")
    void externalEventNoIsRoutedToo() {
        bus(consumer(true, "N")).publish("EXT-1", new Evt("N"));
        verify(notifications).publish(org.mockito.ArgumentMatchers.eq("EXT-1"), any());
        verify(mapper, never()).insertIfAbsent(any(SysOutbox.class));
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
