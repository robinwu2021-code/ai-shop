package ai.neargo.shop.pay.svc.client.port;
import ai.neargo.shop.pay.client.EntitlementRemoteCalls;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.svc.ServiceName;
import org.springframework.beans.factory.ObjectProvider;
public class CoreLegacyCouponAudiencePortImpl implements LegacyCouponAudiencePort {
    private final ObjectProvider<CoreEntitlementInternalApi> api;
    public CoreLegacyCouponAudiencePortImpl(ObjectProvider<CoreEntitlementInternalApi> api) {this.api=api;}
    public Fact resolve(EntitlementContext ctx,Query query) {
        return EntitlementRemoteCalls.call(ServiceName.PLATFORM,()->api.getObject().legacyCouponAudience(new EntitlementCoreInternal.LegacyAudience(ctx,query)));
    }
}
