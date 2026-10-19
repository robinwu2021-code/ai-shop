package ai.neargo.shop.pay.svc.client.port;

import ai.neargo.shop.pay.client.EntitlementRemoteCalls;
import ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.svc.ServiceName;
import org.springframework.beans.factory.ObjectProvider;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;

public class CoreSourceDecisionPortImpl implements SourceDecisionPort {
    private final ObjectProvider<CoreEntitlementInternalApi> api;
    public CoreSourceDecisionPortImpl(ObjectProvider<CoreEntitlementInternalApi> api) { this.api = api; }
    @Override public SourceDecision query(EntitlementContext ctx, SourceRef source) {
        return EntitlementRemoteCalls.call(ServiceName.PLATFORM,
                () -> api.getObject().decision(new EntitlementCoreInternal.SourceQuery(ctx, source)));
    }
    @Override public AuthorizationDecision authorization(EntitlementContext ctx, String ref) {
        return EntitlementRemoteCalls.call(ServiceName.PLATFORM,
                () -> api.getObject().authorization(new EntitlementCoreInternal.AuthorizationQuery(ctx, ref)));
    }
}
