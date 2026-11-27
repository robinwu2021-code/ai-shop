package ai.neargo.shop.invbridge;

import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.broker.BrokerMessage;
import ai.neargo.shop.event.broker.BrokerProperties;
import ai.neargo.shop.event.broker.BrokerSubscription;
import ai.neargo.shop.event.broker.BrokerSubscriptions;
import ai.neargo.shop.event.broker.MessageBroker;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 商品→库存域切到中间件（ADR-067 第 4 步）：四个投影合成一个保序订阅，经中间件封装照样写到库存。 */
@DisplayName("商品→库存域经中间件投递")
class InventoryProductBrokerTest {

    private final InventoryAclService acl = mock(InventoryAclService.class);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("默认名单就是这四个事件")
    void defaultRelayListMatches() throws Exception {
        var sources = new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"));
        String raw = String.valueOf(sources.getFirst().getProperty("shop.events.outbox-event-types"));
        String defaults = raw.substring(raw.indexOf(':') + 1, raw.lastIndexOf('}'));
        assertThat(Arrays.stream(defaults.split(",")).map(String::trim).collect(Collectors.toSet()))
                .isEqualTo(InventoryProductEvents.TYPES);
    }

    @Test
    @DisplayName("四个投影一个保序订阅；同一 SKU 先建档后下架，库存按这个顺序收到")
    void oneOrderedSubscriptionInOrder() {
        Map<BrokerSubscription, Consumer<BrokerMessage>> subs = new LinkedHashMap<>();
        MessageBroker broker = new MessageBroker() {
            @Override public void publish(BrokerMessage m) {
                subs.forEach((s, h) -> { if (s.eventTypes().contains(m.eventType())) h.accept(m); });
            }
            @Override public void subscribe(BrokerSubscription s, Consumer<BrokerMessage> h) { subs.put(s, h); }
        };
        OutboxConsumer[] consumers = {
                new InventoryItemProjectionConsumer(acl, json), new InventorySkuRetiredConsumer(acl, json),
                new InventoryOnSaleConsumer(acl, json), new InventoryInvModeConsumer(acl, json)};
        var props = new BrokerProperties();
        props.setOutboxEventTypes(InventoryProductEvents.TYPES);
        @SuppressWarnings("unchecked") ObjectProvider<MessageBroker> brokers = mock(ObjectProvider.class);
        when(brokers.getIfAvailable()).thenReturn(broker);
        @SuppressWarnings("unchecked") ObjectProvider<OutboxConsumer> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> Stream.of(consumers));
        new BrokerSubscriptions(brokers, provider, props, new MockEnvironment()).start();

        assertThat(subs.keySet()).containsExactly(
                new BrokerSubscription("inventory-product", InventoryProductEvents.TYPES, true));

        broker.publish(new BrokerMessage("EV-1", "SKU", "S-1", "SKU_UPSERTED",
                "{\"skuNo\":\"S-1\",\"entityNo\":\"E-1\",\"title\":\"苹果\",\"invManaged\":true}"));
        broker.publish(new BrokerMessage("EV-2", "SKU", "S-1", "SKU_RETIRED",
                "{\"skuNo\":\"S-1\",\"entityNo\":\"E-1\",\"replacedBy\":[]}"));
        var order = inOrder(acl);
        order.verify(acl).upsertItem("E-1", "S-1", "苹果", null, null, null, null);
        order.verify(acl).retireItem("E-1", "S-1", null);
    }
}
