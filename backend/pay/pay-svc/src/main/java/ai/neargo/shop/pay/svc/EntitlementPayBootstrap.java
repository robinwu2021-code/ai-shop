package ai.neargo.shop.pay.svc;

import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort;
import ai.neargo.shop.spi.entitlement.EntitlementCapabilityPort;
import ai.neargo.shop.pay.entitlement.port.EntitlementCapabilityPortImpl;
import ai.neargo.shop.pay.entitlement.port.EntitlementOwnerRegistryPortImpl;
import ai.neargo.shop.pay.migration.service.EntitlementOwnerRegistryService;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort;
import ai.neargo.shop.pay.entitlement.port.EntitlementMarketingFactsPortImpl;
import ai.neargo.shop.pay.entitlement.service.EntitlementMarketingFactsService;
import ai.neargo.shop.spi.storedvalue.StoredValueCapabilityPort;
import ai.neargo.shop.pay.storedvalue.port.StoredValueCapabilityPortImpl;
import ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties;
import ai.neargo.shop.pay.entitlement.port.EntitlementCatalogPortImpl;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort;
import ai.neargo.shop.pay.entitlement.port.EntitlementGrantPortImpl;
import ai.neargo.shop.pay.entitlement.port.EntitlementSaleReservationPortImpl;
import ai.neargo.shop.pay.entitlement.service.EntitlementSaleReservationService;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort;
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
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort;
import ai.neargo.shop.pay.storedvalue.port.StoredValueUseQuotePortImpl;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRedemptionService;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementOutboxRelay;
import ai.neargo.shop.pay.mapper.entitlement.PayEntOutboxMapper;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventBridgePort;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** A single pay resource, with the explicit transaction name also used in embedded deployment. */
@Configuration(proxyBeanMethods = false)
public class EntitlementPayBootstrap {
    @Bean(name = {"transactionManager", "payTxManager"})
    DataSourceTransactionManager payTransactions(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
    @Bean
    EntitlementCatalogPort entitlementCatalogPort(EntitlementCatalogService catalog,
                                                PayEntitlementFeatureGate features) {
        return new EntitlementCatalogPortImpl(catalog, features);
    }
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
            ai.neargo.shop.pay.entitlement.service.EntitlementEventDeliveryService delivery) {
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
}
