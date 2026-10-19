package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.svc.client.port.CoreEntitlementUseSourcePortImpl;
import ai.neargo.shop.pay.svc.client.port.CoreSourceDecisionPortImpl;
import ai.neargo.shop.pay.svc.client.port.CoreEntitlementIssueAudiencePortImpl;
import ai.neargo.shop.pay.svc.client.port.CoreEntitlementGrantSourcePortImpl;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort;
import ai.neargo.shop.spi.storedvalue.RechargeSourcePort;
import ai.neargo.shop.pay.svc.client.port.CoreRechargeSourcePortImpl;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort;
import ai.neargo.shop.spi.entitlement.EntitlementGrantSourcePort;
import ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventBridgePort;
import ai.neargo.shop.pay.svc.client.port.CorePayEntitlementEventBridgePortImpl;
import tools.jackson.databind.ObjectMapper;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;

/** Standalone pay uses the existing service locator and token client; it never loads core business modules. */
@Configuration(proxyBeanMethods = false)
@Import({ConfigServiceLocator.class, InternalHttp.class})
public class EntitlementCoreClientConfiguration {
    @Bean @Lazy
    CoreEntitlementInternalApi coreEntitlementInternalApi(InternalHttp http) {
        return http.client(ServiceName.PLATFORM, CoreEntitlementInternalApi.class, Duration.ofSeconds(5));
    }
    @Bean
    EntitlementUseSourcePort coreEntitlementUseSourcePort(ObjectProvider<CoreEntitlementInternalApi> api) {
        return new CoreEntitlementUseSourcePortImpl(api);
    }
    @Bean
    SourceDecisionPort coreSourceDecisionPort(ObjectProvider<CoreEntitlementInternalApi> api) {
        return new CoreSourceDecisionPortImpl(api);
    }
    @Bean
    EntitlementIssueAudiencePort coreEntitlementIssueAudiencePort(ObjectProvider<CoreEntitlementInternalApi> api) {
        return new CoreEntitlementIssueAudiencePortImpl(api);
    }
    @Bean
    EntitlementGrantSourcePort coreEntitlementGrantSourcePort(ObjectProvider<CoreEntitlementInternalApi> api) {
        return new CoreEntitlementGrantSourcePortImpl(api);
    }

    @Bean
    PayEntitlementEventBridgePort corePayEntitlementEventBridgePort(ObjectProvider<CoreEntitlementInternalApi> api,
                                                                  ObjectMapper json) {
        return new CorePayEntitlementEventBridgePortImpl(api, json);
    }

    @Bean
    RechargeSourcePort coreRechargeSourcePort(ObjectProvider<CoreEntitlementInternalApi> api) {
        return new CoreRechargeSourcePortImpl(api);
    }

}
