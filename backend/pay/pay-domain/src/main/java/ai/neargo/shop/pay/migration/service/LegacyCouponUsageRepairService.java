package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.spi.entitlement.EntitlementContext;

/** Domain contract; transactional behavior remains in the implementation. */
public interface LegacyCouponUsageRepairService {
    Batch repair(EntitlementContext context,long afterId,int limit);
public record Batch(int scanned,int repaired,int failed,long nextId) { }
}
