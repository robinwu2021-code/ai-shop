package ai.neargo.shop.logisticsbridge;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.broker.BrokerMessage;
import ai.neargo.shop.event.broker.BrokerProperties;
import ai.neargo.shop.event.broker.BrokerSubscription;
import ai.neargo.shop.event.broker.BrokerSubscriptions;
import ai.neargo.shop.event.broker.MessageBroker;
import ai.neargo.shop.trade.order.service.ship.ShipmentSnapshotConverger;
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
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发货快照回写切到中间件（ADR-067 第 4 步）：只有一个消费者，按运单号保序；
 * 收敛本身锁行、只前进，重复或晚到的快照无害，所以不另设订阅组。
 */
@DisplayName("发货快照回写经中间件投递")
class ShipmentSnapshotBrokerTest {

    @Test
    @DisplayName("默认名单包含发货快照")
    void defaultRelayListContainsSnapshot() throws Exception {
        var sources = new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"));
        String raw = String.valueOf(sources.getFirst().getProperty("shop.events.outbox-event-types"));
        String defaults = raw.substring(raw.indexOf(':') + 1, raw.lastIndexOf('}'));
        assertThat(Arrays.stream(defaults.split(",")).map(String::trim).collect(Collectors.toSet()))
                .contains("LGS_SHIPMENT_SNAPSHOT");
    }

    @Test
    @DisplayName("登记成保序订阅，收到后把冻结的快照字段原样交给收敛")
    void subscribesOrderedAndConverges() {
        Map<BrokerSubscription, Consumer<BrokerMessage>> subs = new LinkedHashMap<>();
        MessageBroker broker = new MessageBroker() {
            @Override public void publish(BrokerMessage m) {
                subs.forEach((s, h) -> { if (s.eventTypes().contains(m.eventType())) h.accept(m); });
            }
            @Override public void subscribe(BrokerSubscription s, Consumer<BrokerMessage> h) { subs.put(s, h); }
        };
        var converger = mock(ShipmentSnapshotConverger.class);
        OutboxConsumer consumer = new ShipmentSnapshotConsumer(converger, new ObjectMapper());
        var props = new BrokerProperties();
        props.setOutboxEventTypes(Set.of("LGS_SHIPMENT_SNAPSHOT"));
        @SuppressWarnings("unchecked") ObjectProvider<MessageBroker> brokers = mock(ObjectProvider.class);
        when(brokers.getIfAvailable()).thenReturn(broker);
        @SuppressWarnings("unchecked") ObjectProvider<OutboxConsumer> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> Stream.of(consumer));
        new BrokerSubscriptions(brokers, provider, props, new MockEnvironment()).start();

        assertThat(subs.keySet()).containsExactly(
                new BrokerSubscription("shipment-snapshot", Set.of("LGS_SHIPMENT_SNAPSHOT"), true));
        broker.publish(new BrokerMessage("EV-1", "SHIPMENT", "SH-1", "LGS_SHIPMENT_SNAPSHOT",
                "{\"shipmentNo\":\"SH-1\",\"version\":0,\"waybillNo\":\"SF1\",\"status\":\"DELIVERED\","
                        + "\"signedAt\":2000,\"signedNodeAt\":2000,\"firstTraceAt\":1500,\"source\":\"KD100\"}"));
        verify(converger).converge("SH-1", 0L, "SF1", "DELIVERED", 2000L, 2000L, 1500L, "KD100");
    }
}
