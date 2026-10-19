package ai.neargo.shop.payclient.port;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayEntitlementRequests;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;

@Component
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteEntitlementUseFactsPort implements EntitlementUseFactsPort {
    private final PayInternalApi pay;
    public RemoteEntitlementUseFactsPort(PayInternalApi pay) { this.pay = pay; }
    @Override public UseFact query(EntitlementContext c, String entity, String no) {
        return RemoteEntitlementCalls.call(() -> pay.entUseFacts(new PayEntitlementRequests.UseFacts(c, entity, no)));
    }
}
