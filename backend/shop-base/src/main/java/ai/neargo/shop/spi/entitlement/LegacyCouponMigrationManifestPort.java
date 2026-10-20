package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Query;
import ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Snapshot;

/** All available source-family records, including tombstones. Durable commit must revalidate the complete content hash. */
public interface LegacyCouponMigrationManifestPort {
    Snapshot exportManifest(EntitlementContext context,Query query);
}
