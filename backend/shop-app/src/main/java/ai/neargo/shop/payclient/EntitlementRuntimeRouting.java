package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.entitlement.port.EntitlementGrantPortImpl;
import ai.neargo.shop.pay.entitlement.port.EntitlementUsePortImpl;
import ai.neargo.shop.pay.entitlement.port.EntitlementQueryPortImpl;
import ai.neargo.shop.pay.entitlement.port.EntitlementUseFactsPortImpl;
import ai.neargo.shop.pay.entitlement.port.PaymentAttemptFactsPortImpl;
import ai.neargo.shop.pay.entitlement.service.EntitlementGrantService;
import ai.neargo.shop.pay.entitlement.service.EntitlementUseService;
import ai.neargo.shop.pay.entitlement.service.EntitlementQueryService;
import ai.neargo.shop.pay.entitlement.service.EntitlementUseWriter;
import ai.neargo.shop.pay.entitlement.service.EntitlementSourceFence;
import ai.neargo.shop.pay.mapper.entitlement.EntUseIntentMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;
import ai.neargo.shop.spi.entitlement.PaymentAttemptFactsPort;
import tools.jackson.databind.ObjectMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvRechargeMapper;
import ai.neargo.shop.pay.storedvalue.port.StoredValueRechargeFactsPortImpl;
import ai.neargo.shop.spi.storedvalue.StoredValueRechargeFactsPort;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementOutboxRelay;
import ai.neargo.shop.pay.mapper.entitlement.PayEntOutboxMapper;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventBridgePort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

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
                                                    PayEntitlementEventBridgePort bridge, ObjectMapper json) {
        return new PayEntitlementOutboxRelay(outbox, bridge, json);
    }

    @Bean
    StoredValueRechargeFactsPort storedValueRechargeFactsPort(SvRechargeMapper recharges, ObjectMapper json) {
        return new StoredValueRechargeFactsPortImpl(recharges, json);
    }

}
