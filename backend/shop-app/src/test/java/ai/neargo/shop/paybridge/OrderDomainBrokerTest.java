package ai.neargo.shop.paybridge;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.broker.BrokerMessage;
import ai.neargo.shop.event.broker.BrokerProperties;
import ai.neargo.shop.event.broker.BrokerSubscription;
import ai.neargo.shop.event.broker.BrokerSubscriptions;
import ai.neargo.shop.event.broker.MessageBroker;
import ai.neargo.shop.invbridge.FulfillmentStockIssueConsumer;
import ai.neargo.shop.logistics.registration.ShipmentRegistrar;
import ai.neargo.shop.paybridge.port.ShippingUploadPortImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 订单类事件切到中间件（ADR-067 第 4 步）。物流登记与微信发货上传入队写的是同一次发货、上传在登记之后才有意义，
 * 原来同一事务一起提交 —— 同组 {@code order-shipping}，才不会让上传先于登记完成。
 */
@DisplayName("订单类事件经中间件投递")
class OrderDomainBrokerTest {

    static final Set<String> ORDER_TYPES = Set.of("ORDER_CREATED", "ORDER_PAID", "SUB_ORDER_SHIPPED",
            "SUB_ORDER_COMPLETED", "AFTER_SALE_REFUNDED", "CARD_SALE_CANCELLED");

    @Test
    @DisplayName("默认名单包含订单类六个事件（含卡券取消：卡券消费者收的两类要一起走中间件）")
    void defaultRelayListContainsOrderEvents() throws Exception {
        var sources = new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"));
        String raw = String.valueOf(sources.getFirst().getProperty("shop.events.outbox-event-types"));
        String defaults = raw.substring(raw.indexOf(':') + 1, raw.lastIndexOf('}'));
        assertThat(Arrays.stream(defaults.split(",")).map(String::trim).collect(Collectors.toSet()))
                .containsAll(ORDER_TYPES);
    }

    @Test
    @DisplayName("物流登记与上传入队合成一个保序订阅；库存出库自己一个，发货与完成按子单号保序")
    void shippingConsumersShareOneSubscription() {
        Map<BrokerSubscription, Consumer<BrokerMessage>> subs = new LinkedHashMap<>();
        MessageBroker broker = new MessageBroker() {
            @Override public void publish(BrokerMessage m) { }
            @Override public void subscribe(BrokerSubscription s, Consumer<BrokerMessage> h) { subs.put(s, h); }
        };
        OutboxConsumer[] consumers = {
                mock(ShipmentRegistrar.class, CALLS_REAL_METHODS),
                mock(ShippingUploadPortImpl.class, CALLS_REAL_METHODS),
                mock(FulfillmentStockIssueConsumer.class, CALLS_REAL_METHODS)};
        var props = new BrokerProperties();
        props.setOutboxEventTypes(ORDER_TYPES);
        @SuppressWarnings("unchecked") ObjectProvider<MessageBroker> brokers = mock(ObjectProvider.class);
        when(brokers.getIfAvailable()).thenReturn(broker);
        @SuppressWarnings("unchecked") ObjectProvider<OutboxConsumer> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> Stream.of(consumers));
        new BrokerSubscriptions(brokers, provider, props, new MockEnvironment()).start();

        assertThat(subs.keySet()).containsExactly(
                new BrokerSubscription("order-shipping", Set.of("SUB_ORDER_SHIPPED", "AFTER_SALE_REFUNDED"), true),
                new BrokerSubscription("fulfillment-stock-issue", Set.of("SUB_ORDER_SHIPPED", "SUB_ORDER_COMPLETED"), true));
    }
}
