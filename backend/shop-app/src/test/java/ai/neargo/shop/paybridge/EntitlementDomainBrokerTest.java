package ai.neargo.shop.paybridge;

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
 * 权益与旧券切到中间件（ADR-067 第 4 步）。规则“一个消费者收的类型要么全进名单要么全不进”
 * 顺着储值、营销效果、权益通知三个订阅外推，名单是 13 个类型；每个消费者都整套收到。
 */
@DisplayName("权益与旧券经中间件投递")
class EntitlementDomainBrokerTest {

    static final Set<String> TYPES = Set.of(
            "ENTITLEMENT_ORDER_USE_PREPARED", "ENTITLEMENT_ORDER_USE_DECIDED", "LEGACY_COUPON_CHECKOUT_DECIDED",
            "LEGACY_COUPON_HISTORY_RETURN_DECIDED", "ACTIVITY_BENEFIT_DECIDED", "STORED_VALUE_RECHARGE_PREPARED",
            "STORED_VALUE_RECHARGE_RECEIVED", "STORED_VALUE_RECHARGE_CANCELLED", "STORED_VALUE_CREDITED",
            "ENTITLEMENT_GRANTED", "ENTITLEMENT_USED", "ENTITLEMENT_USE_REVERSED", "LEGACY_COUPON_USAGE_CHANGED");

    @Test
    @DisplayName("默认名单包含这 13 个类型")
    void defaultRelayListContainsEntitlementEvents() throws Exception {
        var sources = new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"));
        String raw = String.valueOf(sources.getFirst().getProperty("shop.events.outbox-event-types"));
        String defaults = raw.substring(raw.indexOf(':') + 1, raw.lastIndexOf('}'));
        assertThat(Arrays.stream(defaults.split(",")).map(String::trim).collect(Collectors.toSet()))
                .containsAll(TYPES);
    }

    @Test
    @DisplayName("每个权益消费者一个保序订阅，收齐自己支持的全部类型")
    void eachConsumerGetsItsWholeTypeSet() {
        Map<BrokerSubscription, Consumer<BrokerMessage>> subs = new LinkedHashMap<>();
        MessageBroker broker = new MessageBroker() {
            @Override public void publish(BrokerMessage m) { }
            @Override public void subscribe(BrokerSubscription s, Consumer<BrokerMessage> h) { subs.put(s, h); }
        };
        OutboxConsumer[] consumers = {
                mock(StoredValueRechargeEventConsumer.class, CALLS_REAL_METHODS),
                mock(EntitlementMarketingEffectSubscriber.class, CALLS_REAL_METHODS),
                mock(EntitlementNotificationSubscriber.class, CALLS_REAL_METHODS),
                mock(EntitlementOrderUseDecisionConsumer.class, CALLS_REAL_METHODS)};
        var props = new BrokerProperties();
        props.setOutboxEventTypes(TYPES);
        @SuppressWarnings("unchecked") ObjectProvider<MessageBroker> brokers = mock(ObjectProvider.class);
        when(brokers.getIfAvailable()).thenReturn(broker);
        @SuppressWarnings("unchecked") ObjectProvider<OutboxConsumer> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> Stream.of(consumers));
        new BrokerSubscriptions(brokers, provider, props, new MockEnvironment()).start();

        assertThat(subs.keySet()).containsExactly(
                new BrokerSubscription("stored-value-recharge-recovery", Set.of("STORED_VALUE_RECHARGE_PREPARED",
                        "STORED_VALUE_RECHARGE_RECEIVED", "STORED_VALUE_RECHARGE_CANCELLED", "STORED_VALUE_CREDITED"), true),
                new BrokerSubscription("entitlement-marketing-effect", Set.of("STORED_VALUE_RECHARGE_RECEIVED",
                        "STORED_VALUE_CREDITED", "ENTITLEMENT_GRANTED", "ENTITLEMENT_USED", "ENTITLEMENT_USE_REVERSED"), true),
                new BrokerSubscription("entitlement-notification", Set.of("ENTITLEMENT_GRANTED", "ENTITLEMENT_USED",
                        "STORED_VALUE_CREDITED", "LEGACY_COUPON_USAGE_CHANGED"), true),
                new BrokerSubscription("entitlement-order-use-decision",
                        Set.of("ENTITLEMENT_ORDER_USE_PREPARED", "ENTITLEMENT_ORDER_USE_DECIDED"), true));
    }
}
