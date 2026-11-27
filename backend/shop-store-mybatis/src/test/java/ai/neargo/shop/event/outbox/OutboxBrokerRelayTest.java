package ai.neargo.shop.event.outbox;

import ai.neargo.shop.event.DeliveryContext;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.broker.BrokerDeadLetterException;
import ai.neargo.shop.event.broker.BrokerException;
import ai.neargo.shop.event.broker.BrokerMessage;
import ai.neargo.shop.event.broker.BrokerProperties;
import ai.neargo.shop.event.broker.BrokerSubscription;
import ai.neargo.shop.event.broker.BrokerSubscriptions;
import ai.neargo.shop.event.broker.MessageBroker;
import ai.neargo.shop.event.outbox.runtime.OutboxRunner;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 中间件接管统一投递模式（ADR-067 决策 9 的基础能力部分）：统一模式的每条“事件 × 处理者”投递，
 * 名单里的改发中间件（目标 = 处理者、排序键 = 流键），由订阅端调回同一个处理者；处理结果映射成中间件动作。
 */
@DisplayName("中间件接管统一投递模式")
class OutboxBrokerRelayTest extends OutboxTestFixture {

    /** 进程内假中间件：按目标订阅分发，处理器的异常记下来（真中间件里它们触发重投或死信）。 */
    static final class FakeBroker implements MessageBroker {
        final Map<String, Consumer<BrokerMessage>> byName = new LinkedHashMap<>();
        final Map<String, BrokerSubscription> subscriptions = new LinkedHashMap<>();
        final List<BrokerMessage> published = new CopyOnWriteArrayList<>();
        final List<RuntimeException> handlerFailures = new CopyOnWriteArrayList<>();
        boolean down;
        @Override public void publish(BrokerMessage m) {
            if (down) throw new BrokerException("down");
            published.add(m);
            var h = byName.get(m.target());
            if (h == null) throw new BrokerException("unroutable " + m.target());
            try { h.accept(m); } catch (RuntimeException e) { handlerFailures.add(e); }
        }
        @Override public void subscribe(BrokerSubscription s, Consumer<BrokerMessage> h) {
            byName.put(s.name(), h);
            subscriptions.put(s.name(), s);
        }
    }

    private final List<String> handled = new ArrayList<>();

    private OutboxHandler handler(String id, DeliveryResult result) {
        return new OutboxHandler() {
            public String handlerId() { return id; }
            public boolean supports(String type) { return "ORDER_PAID".equals(type); }
            public DeliveryResult handle(OutboxEnvelope e) {
                handled.add(id + ":" + e.eventNo() + ":" + e.streamKey() + ":" + e.sourceSystem() + "@" + DeliveryContext.eventNo());
                return result;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private FakeBroker wire(UnifiedOutboxFacade facade, Set<String> relayed) {
        var broker = new FakeBroker();
        ObjectProvider<MessageBroker> brokers = mock(ObjectProvider.class);
        when(brokers.getIfAvailable()).thenReturn(broker);
        ObjectProvider<OutboxConsumer> consumers = mock(ObjectProvider.class);
        when(consumers.orderedStream()).thenAnswer(inv -> Stream.empty());
        ObjectProvider<UnifiedOutboxFacade> facades = mock(ObjectProvider.class);
        when(facades.getIfAvailable()).thenReturn(facade);
        var props = new BrokerProperties();
        props.setOutboxEventTypes(relayed);
        var subscriptions = new BrokerSubscriptions(brokers, consumers, props,
                new MockEnvironment().withProperty("shop.outbox.sources.platform.mode", "unified"));
        subscriptions.setUnifiedFacade(facades);
        subscriptions.start();
        return broker;
    }

    @Test
    @DisplayName("名单里的投递改发中间件：目标是处理者、排序键是流键；订阅端调回同一处理者，投递标已发")
    void relayedDeliveriesGoThroughBroker() throws Exception {
        append("E1");
        var facade = new UnifiedOutboxFacade(new ObjectMapper());
        facade.register(store, List.of(handler("a", DeliveryResult.sent()), handler("b", DeliveryResult.sent())));
        var broker = wire(facade, Set.of("ORDER_PAID"));

        assertThat(broker.subscriptions.values()).containsExactlyInAnyOrder(
                new BrokerSubscription("a", Set.of("ORDER_PAID"), true),
                new BrokerSubscription("b", Set.of("ORDER_PAID"), true));
        try (var runner = new OutboxRunner(facade, Clock.fixed(now, ZoneOffset.UTC), 2, 10)) {
            runner.recoverDue();
            assertThat(runner.awaitIdle(Duration.ofSeconds(2))).isTrue();
        }
        assertThat(broker.published).extracting(BrokerMessage::target).containsExactlyInAnyOrder("a", "b");
        assertThat(broker.published).allSatisfy(m -> assertThat(m.orderingKey()).isEqualTo("ORDER:O1"));
        assertThat(handled).containsExactlyInAnyOrder("a:E1:ORDER:O1:PLATFORM@E1", "b:E1:ORDER:O1:PLATFORM@E1");
        assertThat(jdbc.queryForList("SELECT status FROM sys_outbox_delivery", String.class)).containsOnly("SENT");
    }

    @Test
    @DisplayName("中间件没确认：投递不标已发、按原退避重试，处理者没被调")
    void brokerDownKeepsDeliveryForRetry() throws Exception {
        append("E2");
        var facade = new UnifiedOutboxFacade(new ObjectMapper());
        facade.register(store, List.of(handler("a", DeliveryResult.sent()), handler("b", DeliveryResult.sent())));
        var broker = wire(facade, Set.of("ORDER_PAID"));
        broker.down = true;
        try (var runner = new OutboxRunner(facade, Clock.fixed(now, ZoneOffset.UTC), 2, 10)) {
            runner.recoverDue();
            assertThat(runner.awaitIdle(Duration.ofSeconds(2))).isTrue();
        }
        assertThat(handled).isEmpty();
        assertThat(jdbc.queryForList("SELECT status FROM sys_outbox_delivery", String.class)).doesNotContain("SENT");
    }

    @Test
    @DisplayName("处理结果映射：永久失败进死信、可重试或结果未知退回重投、成功确认")
    void deliveryResultsMapToBrokerActions() throws Exception {
        append("E3");
        var facade = new UnifiedOutboxFacade(new ObjectMapper());
        facade.register(store, List.of(handler("a", DeliveryResult.failed("BAD", "no")),
                handler("b", DeliveryResult.retry("LATER", "busy"))));
        var broker = wire(facade, Set.of("ORDER_PAID"));
        try (var runner = new OutboxRunner(facade, Clock.fixed(now, ZoneOffset.UTC), 2, 10)) {
            runner.recoverDue();
            assertThat(runner.awaitIdle(Duration.ofSeconds(2))).isTrue();
        }
        assertThat(broker.handlerFailures).hasSize(2);
        assertThat(broker.handlerFailures).filteredOn(e -> e instanceof BrokerDeadLetterException).hasSize(1);
        assertThat(broker.handlerFailures).filteredOn(e -> !(e instanceof BrokerDeadLetterException)).hasSize(1);
    }

    @Test
    @DisplayName("不在名单里的类型照旧本地调处理者")
    void unlistedTypesStayLocal() throws Exception {
        append("E4");
        var facade = new UnifiedOutboxFacade(new ObjectMapper());
        facade.register(store, List.of(handler("a", DeliveryResult.sent()), handler("b", DeliveryResult.sent())));
        var broker = wire(facade, Set.of("SOMETHING_ELSE"));
        try (var runner = new OutboxRunner(facade, Clock.fixed(now, ZoneOffset.UTC), 2, 10)) {
            runner.recoverDue();
            assertThat(runner.awaitIdle(Duration.ofSeconds(2))).isTrue();
        }
        assertThat(broker.published).isEmpty();
        assertThat(handled).hasSize(2);
    }
}
