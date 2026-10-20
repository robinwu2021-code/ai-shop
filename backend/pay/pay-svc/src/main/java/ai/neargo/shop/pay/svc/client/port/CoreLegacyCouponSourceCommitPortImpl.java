package ai.neargo.shop.pay.svc.client.port;

import ai.neargo.shop.pay.client.EntitlementRemoteCalls;
import ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.LegacyCouponSourceCommitPort;
import ai.neargo.shop.svc.ServiceName;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;

/** Unknown durable decision is distinct from unavailable transport. No completion is inferred from timeout. */
public class CoreLegacyCouponSourceCommitPortImpl implements LegacyCouponSourceCommitPort {
    private final ObjectProvider<CoreEntitlementInternalApi> api;
    public CoreLegacyCouponSourceCommitPortImpl(ObjectProvider<CoreEntitlementInternalApi> api) { this.api=api; }
    @Override public Optional<CommitProof> decided(EntitlementContext context,SourceIdentity template) {
        var result=EntitlementRemoteCalls.call(ServiceName.PLATFORM,()->api.getObject().legacyMigrationCommit(
                new EntitlementCoreInternal.MigrationCommitQuery(context,template)));
        return Optional.ofNullable(result.proof());
    }
}
