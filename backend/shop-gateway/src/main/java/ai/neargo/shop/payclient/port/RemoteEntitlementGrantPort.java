package ai.neargo.shop.payclient.port;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayEntitlementRequests;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;

@Component
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteEntitlementGrantPort implements EntitlementGrantPort {
    private final PayInternalApi pay;
    public RemoteEntitlementGrantPort(PayInternalApi pay) { this.pay = pay; }
    @Override public GrantView prepare(EntitlementContext c, GrantCommand q) {
        return RemoteEntitlementCalls.call(() -> pay.entGrantPrepare(new PayEntitlementRequests.GrantPrepare(c, q)));
    }
    @Override public GrantView commit(EntitlementContext c, GrantDecision q) {
        return RemoteEntitlementCalls.call(() -> pay.entGrantCommit(new PayEntitlementRequests.GrantCommit(c, q)));
    }
    @Override public GrantView abort(EntitlementContext c, AbortGrant q) {
        return RemoteEntitlementCalls.call(() -> pay.entGrantAbort(new PayEntitlementRequests.GrantAbort(c, q)));
    }
    @Override public GrantView query(EntitlementContext c, String entity, String no) {
        return RemoteEntitlementCalls.call(() ->
                pay.entGrantQuery(new PayEntitlementRequests.GrantQuery(c, entity, no)));
    }
}
