package ai.neargo.shop.pay.svc.client.port;

import ai.neargo.shop.pay.client.EntitlementRemoteCalls;
import ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal;
import ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort;
import ai.neargo.shop.svc.ServiceName;
import org.springframework.beans.factory.ObjectProvider;

/** Pure pay runtime reads source evidence through the authenticated core client, never core mappers. */
public class CoreLegacyCouponFrozenSnapshotPortImpl implements LegacyCouponFrozenSnapshotPort,ai.neargo.shop.spi.entitlement.LegacyCouponMigrationManifestPort {
    private final ObjectProvider<CoreEntitlementInternalApi> api;
    public CoreLegacyCouponFrozenSnapshotPortImpl(ObjectProvider<CoreEntitlementInternalApi> api) { this.api=api; }
    @Override public Snapshot exportFrozen(EntitlementContext context,Query query) {
        return EntitlementRemoteCalls.call(ServiceName.PLATFORM,
                () -> api.getObject().frozenLegacySnapshot(new EntitlementCoreInternal.FrozenSnapshotQuery(context,query)));
    }
    @Override public Snapshot exportManifest(EntitlementContext context,Query query) {
        return EntitlementRemoteCalls.call(ServiceName.PLATFORM,
                () -> api.getObject().frozenLegacyManifest(new EntitlementCoreInternal.FrozenSnapshotQuery(context,query)));
    }
}
