package ai.neargo.shop.event.broker;

import ai.neargo.shop.event.DeliveryContext;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 与具体中间件无关的订阅编排（ADR-067 决策 9）：用进程内假实现验证，换中间件不影响这些语义。 */
@DisplayName("中间件订阅编排")
class BrokerSubscriptionsTest {

    /** 进程内假中间件：记下订阅，按事件类型分发。 */
    static final class FakeBroker implements MessageBroker {
        final Map<BrokerSubscription, Consumer<BrokerMessage>> subs = new LinkedHashMap<>();
        @Override public void publish(BrokerMessage m) {
            subs.forEach((s, h) -> { if (s.eventTypes().contains(m.eventType())) h.accept(m); });
        }
        @Override public void subscribe(BrokerSubscription s, Consumer<BrokerMessage> h) { subs.put(s, h); }
    }

    private final List<String> seen = new ArrayList<>();

    private OutboxConsumer consumer(String id, boolean afterCommit, Set<String> declared, String... supported) {
        return new OutboxConsumer() {
            @Override public String handlerId() { return id; }
            @Override public boolean afterCommitOnly() { return afterCommit; }
            @Override public Set<String> eventTypes() { return declared; }
            @Override public boolean supports(String t) { return Set.of(supported).contains(t); }
            @Override public void consume(SysOutbox e) { seen.add(id + ":" + e.getEventType() + "@" + DeliveryContext.eventNo()); }
        };
    }

    @SuppressWarnings("unchecked")
    private BrokerSubscriptions orchestration(FakeBroker broker, BrokerProperties props, MockEnvironment env,
                                              OutboxConsumer... consumers) {
        ObjectProvider<MessageBroker> brokers = mock(ObjectProvider.class);
        when(brokers.getIfAvailable()).thenReturn(broker);
        ObjectProvider<OutboxConsumer> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> Stream.of(consumers));
        return new BrokerSubscriptions(brokers, provider, props, env);
    }

    @Test
    @DisplayName("通知类按 eventTypes 不保序订阅；必达只订“改由中间件投递”名单里它支持的、保序；收到时带事件号调 consume")
    void subscribesByKind() {
        var broker = new FakeBroker();
        var props = new BrokerProperties();
        props.setOutboxEventTypes(Set.of("ORDER_PAID", "SKU_UPSERTED"));
        orchestration(broker, props, new MockEnvironment(),
                consumer("notification", true, Set.of("ORDER_PAID", "REVIEW_CREATED"), "ORDER_PAID", "REVIEW_CREATED"),
                consumer("card-sale", false, Set.of(), "ORDER_PAID", "CARD_SALE_CANCELLED"),
                consumer("inventory", false, Set.of(), "SKU_RETIRED")).start();

        assertThat(broker.subs.keySet()).containsExactly(
                new BrokerSubscription("notification", Set.of("ORDER_PAID", "REVIEW_CREATED"), false),
                new BrokerSubscription("card-sale", Set.of("ORDER_PAID"), true));

        broker.publish(new BrokerMessage("EV-1", "ORDER", "O-1", "ORDER_PAID", "{}"));
        assertThat(seen).containsExactly("notification:ORDER_PAID@EV-1", "card-sale:ORDER_PAID@EV-1");
    }

    @Test
    @DisplayName("同一订阅组的必达消费者合成一个保序订阅：一条队列、同一聚合按序，组内按原顺序逐个调")
    void groupedDurableConsumersShareOneOrderedSubscription() {
        var broker = new FakeBroker();
        var props = new BrokerProperties();
        props.setOutboxEventTypes(Set.of("SKU_UPSERTED", "SKU_RETIRED"));
        orchestration(broker, props, new MockEnvironment(),
                grouped("inv-item", "inventory-product", "SKU_UPSERTED"),
                grouped("inv-retire", "inventory-product", "SKU_RETIRED")).start();

        assertThat(broker.subs.keySet()).containsExactly(
                new BrokerSubscription("inventory-product", Set.of("SKU_UPSERTED", "SKU_RETIRED"), true));
        broker.publish(new BrokerMessage("EV-1", "SKU", "S-1", "SKU_UPSERTED", "{}"));
        broker.publish(new BrokerMessage("EV-2", "SKU", "S-1", "SKU_RETIRED", "{}"));
        assertThat(seen).containsExactly("inv-item:SKU_UPSERTED@EV-1", "inv-retire:SKU_RETIRED@EV-2");
    }

    private OutboxConsumer grouped(String id, String group, String type) {
        return new OutboxConsumer() {
            @Override public String handlerId() { return id; }
            @Override public String subscriptionGroup() { return group; }
            @Override public boolean supports(String t) { return type.equals(t); }
            @Override public void consume(SysOutbox e) { seen.add(id + ":" + e.getEventType() + "@" + DeliveryContext.eventNo()); }
        };
    }

    @Test
    @DisplayName("没有中间件实现：什么都不登记")
    @SuppressWarnings("unchecked")
    void noBrokerNoop() {
        ObjectProvider<MessageBroker> none = mock(ObjectProvider.class);
        ObjectProvider<OutboxConsumer> consumers = mock(ObjectProvider.class);
        new BrokerSubscriptions(none, consumers, new BrokerProperties(), new MockEnvironment()).start();
        org.mockito.Mockito.verifyNoInteractions(consumers);
    }

    @Test
    @DisplayName("通知类没声明 eventTypes、或统一投递模式下开中间件中转：启动即失败")
    void misconfigurationFailsFast() {
        assertThatThrownBy(() -> orchestration(new FakeBroker(), new BrokerProperties(), new MockEnvironment(),
                consumer("print-notify", true, Set.of(), "PRINT_X")).start())
                .hasMessageContaining("eventTypes");
        var props = new BrokerProperties();
        props.setOutboxEventTypes(Set.of("ORDER_PAID"));
        assertThatThrownBy(() -> orchestration(new FakeBroker(), props,
                new MockEnvironment().withProperty("shop.outbox.sources.platform.mode", "unified")).start())
                .hasMessageContaining("unified");
    }
}
