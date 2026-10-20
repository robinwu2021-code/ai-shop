package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementOutboxRelay;
import ai.neargo.shop.pay.entitlement.port.EntitlementCapabilityPortImpl;
import ai.neargo.shop.pay.entitlement.port.EntitlementGrantPortImpl;
import ai.neargo.shop.pay.entitlement.port.EntitlementMarketingFactsPortImpl;
import ai.neargo.shop.pay.entitlement.port.EntitlementOwnerRegistryPortImpl;
import ai.neargo.shop.pay.entitlement.port.EntitlementQueryPortImpl;
import ai.neargo.shop.pay.entitlement.port.EntitlementSaleReservationPortImpl;
import ai.neargo.shop.pay.entitlement.port.EntitlementUseFactsPortImpl;
import ai.neargo.shop.pay.entitlement.port.EntitlementUsePortImpl;
import ai.neargo.shop.pay.entitlement.port.LegacyCouponUsePortImpl;
import ai.neargo.shop.pay.entitlement.port.LegacyCouponHistoricalReturnPortImpl;
import ai.neargo.shop.pay.entitlement.port.PaymentAttemptFactsPortImpl;
import ai.neargo.shop.pay.entitlement.service.EntitlementEventDeliveryService;
import ai.neargo.shop.pay.entitlement.service.EntitlementGrantService;
import ai.neargo.shop.pay.entitlement.service.EntitlementMarketingFactsService;
import ai.neargo.shop.pay.entitlement.service.EntitlementQueryService;
import ai.neargo.shop.pay.entitlement.service.EntitlementSaleReservationService;
import ai.neargo.shop.pay.entitlement.service.EntitlementSourceFence;
import ai.neargo.shop.pay.entitlement.service.EntitlementUseService;
import ai.neargo.shop.pay.entitlement.service.EntitlementUseWriter;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseIntentMapper;
import ai.neargo.shop.pay.mapper.entitlement.PayEntOutboxMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvRechargeMapper;
import ai.neargo.shop.pay.migration.service.EntitlementOwnerRegistryService;
import ai.neargo.shop.pay.migration.service.LegacyCouponHistoricalReturnService;
import ai.neargo.shop.pay.migration.service.LegacyCouponUseService;
import ai.neargo.shop.pay.storedvalue.port.StoredValueCapabilityPortImpl;
import ai.neargo.shop.pay.storedvalue.port.StoredValueRechargeFactsPortImpl;
import ai.neargo.shop.pay.storedvalue.port.StoredValueUseQuotePortImpl;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRedemptionService;
import ai.neargo.shop.spi.entitlement.EntitlementCapabilityPort;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.entitlement.LegacyCouponHistoricalReturnPort;
import ai.neargo.shop.spi.entitlement.LegacyCouponUsePort;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventBridgePort;
import ai.neargo.shop.spi.entitlement.PaymentAttemptFactsPort;
import ai.neargo.shop.spi.storedvalue.StoredValueCapabilityPort;
import ai.neargo.shop.spi.storedvalue.StoredValueRechargeFactsPort;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Only embedded main-app callers receive local owner adapters; pay-svc always binds its own owner. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "embedded", matchIfMissing = true)
public class EntitlementRuntimeRouting {
    @Bean
    EntitlementGrantPort entitlementGrantPort(EntitlementGrantService service) {
        return new EntitlementGrantPortImpl(service);
    }
    @Bean
    EntitlementUsePort entitlementUsePort(EntitlementUseService service) {
        return new EntitlementUsePortImpl(service);
    }
    @Bean
    EntitlementQueryPort entitlementQueryPort(EntitlementQueryService service) {
        return new EntitlementQueryPortImpl(service);
    }
    @Bean
    EntitlementUseFactsPort entitlementUseFactsPort(EntUseIntentMapper intents, EntitlementUseWriter writer,
                                                   EntitlementSourceFence fences, ObjectMapper json) {
        return new EntitlementUseFactsPortImpl(intents, writer, fences, json);
    }
    @Bean
    PaymentAttemptFactsPort paymentAttemptFactsPort(PaymentMapper mapper) {
        return new PaymentAttemptFactsPortImpl(mapper);
    }

    @Bean
    PayEntitlementOutboxRelay entitlementOutboxRelay(PayEntOutboxMapper outbox,
                                                    PayEntitlementEventBridgePort bridge, ObjectMapper json,
            EntitlementEventDeliveryService delivery) {
        return new PayEntitlementOutboxRelay(outbox, bridge, json, delivery);
    }

    @Bean
    StoredValueRechargeFactsPort storedValueRechargeFactsPort(SvRechargeMapper recharges, ObjectMapper json) {
        return new StoredValueRechargeFactsPortImpl(recharges, json);
    }

    @Bean
    StoredValueUseQuotePort storedValueUseQuotePort(StoredValueRedemptionService service) {
        return new StoredValueUseQuotePortImpl(service);
    }

    @Bean
    EntitlementSaleReservationPort entitlementSaleReservationPort(EntitlementSaleReservationService service) {
        return new EntitlementSaleReservationPortImpl(service);
    }
    @Bean
    StoredValueCapabilityPort storedValueCapabilityPort(PayEntitlementProperties properties) {
        return new StoredValueCapabilityPortImpl(properties);
    }
    @Bean
    EntitlementMarketingFactsPort entitlementMarketingFactsPort(EntitlementMarketingFactsService service) {
        return new EntitlementMarketingFactsPortImpl(service);
    }
    @Bean
    EntitlementOwnerRegistryPort entitlementOwnerRegistryPort(EntitlementOwnerRegistryService service) {
        return new EntitlementOwnerRegistryPortImpl(service);
    }
    @Bean
    EntitlementCapabilityPort entitlementCapabilityPort(PayEntitlementProperties properties) {
        return new EntitlementCapabilityPortImpl(properties);
    }
    @Bean
    LegacyCouponUsePort legacyCouponUsePort(
            LegacyCouponUseService service) {
        return new LegacyCouponUsePortImpl(service);
    }
    @Bean
    LegacyCouponHistoricalReturnPort legacyCouponHistoricalReturnPort(
            LegacyCouponHistoricalReturnService service) {
        return new LegacyCouponHistoricalReturnPortImpl(service);
    }
}
