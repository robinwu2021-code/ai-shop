package ai.neargo.shop.pay.svc.client.port;

import ai.neargo.shop.pay.client.EntitlementRemoteCalls;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.svc.ServiceName;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;

/** Same source protocol in a separate pay process; null transport envelopes are never negative decisions. */
public class CoreLegacyCouponCheckoutSourcePortImpl implements LegacyCouponCheckoutSourcePort {
    private final ObjectProvider<CoreEntitlementInternalApi> api;
    public CoreLegacyCouponCheckoutSourcePortImpl(ObjectProvider<CoreEntitlementInternalApi> api) { this.api=api; }
    @Override public CheckoutFact resolve(EntitlementContext ctx,String checkoutNo) {
        return EntitlementRemoteCalls.call(ServiceName.PLATFORM,()->api.getObject().legacyCheckoutSource(
                new EntitlementCoreInternal.LegacyCheckoutQuery(ctx,checkoutNo)));
    }
    @Override public Optional<Decision> decision(EntitlementContext ctx,String checkoutNo) {
        var result=EntitlementRemoteCalls.call(ServiceName.PLATFORM,()->api.getObject().legacyCheckoutDecision(
                new EntitlementCoreInternal.LegacyCheckoutQuery(ctx,checkoutNo)));
        return Optional.ofNullable(result.decision());
    }
    @Override public Optional<Reversal> reversal(EntitlementContext ctx,String checkoutNo) {
        var result=EntitlementRemoteCalls.call(ServiceName.PLATFORM,()->api.getObject().legacyCheckoutReversal(
                new EntitlementCoreInternal.LegacyCheckoutQuery(ctx,checkoutNo)));
        return Optional.ofNullable(result.reversal());
    }
}
