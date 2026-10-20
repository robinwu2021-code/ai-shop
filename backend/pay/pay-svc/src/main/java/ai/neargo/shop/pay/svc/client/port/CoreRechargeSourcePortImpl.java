package ai.neargo.shop.pay.svc.client.port;

import ai.neargo.shop.pay.client.EntitlementRemoteCalls;
import ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.storedvalue.RechargeSourcePort;
import ai.neargo.shop.svc.ServiceName;
import org.springframework.beans.factory.ObjectProvider;

public class CoreRechargeSourcePortImpl implements RechargeSourcePort {
    private final ObjectProvider<CoreEntitlementInternalApi> api;
    public CoreRechargeSourcePortImpl(ObjectProvider<CoreEntitlementInternalApi> api) { this.api = api; }
    @Override public SourceFact resolve(EntitlementContext ctx, SourceRef source) {
        return EntitlementRemoteCalls.call(ServiceName.PLATFORM,
                () -> api.getObject().rechargeSource(new EntitlementCoreInternal.SourceQuery(ctx, source)));
    }
    @Override public SourceFact byRechargeNo(EntitlementContext ctx, String no) {
        return EntitlementRemoteCalls.call(ServiceName.PLATFORM,
                () -> api.getObject().rechargeByNo(new EntitlementCoreInternal.RechargeQuery(ctx, no)));
    }
}
