package ai.neargo.shop.pay.svc.client.port;

import ai.neargo.shop.pay.client.EntitlementRemoteCalls;
import ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.svc.ServiceName;
import org.springframework.beans.factory.ObjectProvider;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort;

public class CoreEntitlementUseSourcePortImpl implements EntitlementUseSourcePort {
    private final ObjectProvider<CoreEntitlementInternalApi> api;
    public CoreEntitlementUseSourcePortImpl(ObjectProvider<CoreEntitlementInternalApi> api) { this.api = api; }
    @Override public UseSourceFact resolve(EntitlementContext ctx, SourceRef source) {
        return EntitlementRemoteCalls.call(ServiceName.PLATFORM,
                () -> api.getObject().useSource(new EntitlementCoreInternal.SourceQuery(ctx, source)));
    }
}
