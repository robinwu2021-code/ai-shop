package ai.neargo.shop.printbridge;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.broker.BrokerMessage;
import ai.neargo.shop.event.broker.BrokerProperties;
import ai.neargo.shop.event.broker.BrokerSubscription;
import ai.neargo.shop.event.broker.BrokerSubscriptions;
import ai.neargo.shop.event.broker.MessageBroker;
import ai.neargo.shop.print.api.PrintFacade;
import ai.neargo.shop.print.api.PrintJobCommandService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 打印事实域切到中间件（ADR-067 第 4 步）：事实与控制变更同组保序，同一订单先建任务、后应用控制。 */
@DisplayName("打印事实域经中间件投递")
class PrintFactBrokerTest {

    private final PrintFacade facts = mock(PrintFacade.class);
    private final PrintJobCommandService controls = mock(PrintJobCommandService.class);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("默认名单包含打印事实三类事件")
    void defaultRelayListContainsPrintFacts() throws Exception {
        var sources = new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"));
        String raw = String.valueOf(sources.getFirst().getProperty("shop.events.outbox-event-types"));
        String defaults = raw.substring(raw.indexOf(':') + 1, raw.lastIndexOf('}'));
        assertThat(Arrays.stream(defaults.split(",")).map(String::trim).collect(Collectors.toSet()))
                .containsAll(PrintFactEvents.TYPES);
    }

    @Test
    @DisplayName("事实与控制变更一个保序订阅；同一订单先事实后控制，打印按这个顺序收到")
    void oneOrderedSubscriptionInOrder() {
        Map<BrokerSubscription, Consumer<BrokerMessage>> subs = new LinkedHashMap<>();
        MessageBroker broker = new MessageBroker() {
            @Override public void publish(BrokerMessage m) {
                subs.forEach((s, h) -> { if (s.eventTypes().contains(m.eventType())) h.accept(m); });
            }
            @Override public void subscribe(BrokerSubscription s, Consumer<BrokerMessage> h) { subs.put(s, h); }
        };
        OutboxConsumer[] consumers = {new PrintFactReadyConsumer(facts, json), new PrintControlChangedConsumer(controls, json)};
        var props = new BrokerProperties();
        props.setOutboxEventTypes(PrintFactEvents.TYPES);
        @SuppressWarnings("unchecked") ObjectProvider<MessageBroker> brokers = mock(ObjectProvider.class);
        when(brokers.getIfAvailable()).thenReturn(broker);
        @SuppressWarnings("unchecked") ObjectProvider<OutboxConsumer> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> Stream.of(consumers));
        new BrokerSubscriptions(brokers, provider, props, new MockEnvironment()).start();

        assertThat(subs.keySet()).containsExactly(
                new BrokerSubscription(PrintFactEvents.SUBSCRIPTION_GROUP, PrintFactEvents.TYPES, true));

        broker.publish(new BrokerMessage("EV-1", "ORDER", "O-1", "ORDER_PRINT_FACT_READY",
                "{\"orderNo\":\"O-1\",\"subOrderNo\":\"S-1\",\"factRef\":null}"));
        broker.publish(new BrokerMessage("EV-2", "ORDER", "O-1", "ORDER_PRINT_CONTROL_CHANGED",
                "{\"fact\":{\"orderNo\":\"O-1\",\"affectedFacts\":[]}}"));
        var order = inOrder(facts, controls);
        order.verify(facts).onFact(any());
        order.verify(controls).applyControl(any());
    }
}
