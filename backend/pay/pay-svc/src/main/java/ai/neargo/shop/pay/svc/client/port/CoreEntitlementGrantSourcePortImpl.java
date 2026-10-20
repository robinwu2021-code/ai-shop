package ai.neargo.shop.pay.svc.client.port;

import ai.neargo.shop.pay.client.EntitlementRemoteCalls;
import ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.svc.ServiceName;
import org.springframework.beans.factory.ObjectProvider;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.EntitlementGrantSourcePort;
import ai.neargo.shop.spi.entitlement.EntitlementSaleSourcePort;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.ReserveSale;

public class CoreEntitlementGrantSourcePortImpl implements EntitlementGrantSourcePort, EntitlementSaleSourcePort {
    private final ObjectProvider<CoreEntitlementInternalApi> api;
    public CoreEntitlementGrantSourcePortImpl(ObjectProvider<CoreEntitlementInternalApi> api) { this.api = api; }
    @Override public boolean supports(SourceRef source) { return "CORE".equals(source.sourceSystem()); }
    @Override public SourceFact resolve(EntitlementContext ctx, EntitlementGrantPort.GrantCommand command) {
        return EntitlementRemoteCalls.call(ServiceName.PLATFORM,
                () -> api.getObject().grantSource(new EntitlementCoreInternal.GrantSourceQuery(ctx, command)));
    }
    @Override public SourceDecisionPort.SourceDecision decision(EntitlementContext ctx, SourceRef source) {
        return EntitlementRemoteCalls.call(ServiceName.PLATFORM,
                () -> api.getObject().grantDecision(new EntitlementCoreInternal.SourceQuery(ctx, source)));
    }
    @Override public SaleSourceFact saleSource(EntitlementContext ctx, ReserveSale command) {
        return EntitlementRemoteCalls.call(ServiceName.PLATFORM,
                () -> api.getObject().saleSource(new EntitlementCoreInternal.SaleSourceQuery(ctx, command)));
    }
}
