package ai.neargo.shop.promotion.port;

import ai.neargo.shop.promotion.migration.LegacyCouponSourceCommitService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.LegacyCouponSourceCommitPort;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Narrow read adapter so source-proof test overrides cannot replace the core decision writer. */
@Component
public class LegacyCouponSourceCommitPortImpl implements LegacyCouponSourceCommitPort {
    private final LegacyCouponSourceCommitService owner;
    public LegacyCouponSourceCommitPortImpl(LegacyCouponSourceCommitService owner) { this.owner=owner; }
    @Override public Optional<CommitProof> decided(EntitlementContext context,SourceIdentity template) {
        return owner.decided(context,template);
    }
}
