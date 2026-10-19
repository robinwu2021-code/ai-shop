package ai.neargo.shop.pay.svc.client.port;

import ai.neargo.shop.pay.client.EntitlementRemoteCalls;
import ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.svc.ServiceName;
import org.springframework.beans.factory.ObjectProvider;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort;

public class CoreEntitlementIssueAudiencePortImpl implements EntitlementIssueAudiencePort {
    private final ObjectProvider<CoreEntitlementInternalApi> api;
    public CoreEntitlementIssueAudiencePortImpl(ObjectProvider<CoreEntitlementInternalApi> api) { this.api = api; }
    @Override public AudienceSnapshot resolve(EntitlementContext ctx, String entity, String ref) {
        return EntitlementRemoteCalls.call(ServiceName.PLATFORM,
                () -> api.getObject().audience(new EntitlementCoreInternal.AudienceQuery(ctx, entity, ref)));
    }
}
