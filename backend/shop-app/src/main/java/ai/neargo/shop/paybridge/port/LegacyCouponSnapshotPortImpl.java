package ai.neargo.shop.paybridge.port;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort;
import ai.neargo.shop.spi.entitlement.LegacyCouponMigrationManifestPort;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import org.springframework.stereotype.Component;
/** Snapshot ports forward to the Core coordinator, whose fresh transaction owns every source lock. */
@Component
public class LegacyCouponSnapshotPortImpl implements LegacyCouponFrozenSnapshotPort,LegacyCouponMigrationManifestPort {
    private final LegacyCouponSourceFenceService owner;
    public LegacyCouponSnapshotPortImpl(LegacyCouponSourceFenceService owner) {this.owner=owner;}
    public Snapshot exportFrozen(EntitlementContext context,Query query) {return owner.exportFrozen(context,query);}
    public Snapshot exportManifest(EntitlementContext context,Query query) {return owner.exportManifest(context,query);}
}
