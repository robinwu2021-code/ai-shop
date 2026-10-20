package ai.neargo.shop.pay.migration.service;

/** Domain contract; transactional behavior remains in the implementation. */
public interface LegacyCouponMigrationRecoveryService {
    Batch recover(String tenant,long afterId,int limit);
public record Batch(int scanned,int completed,int deferred,int failed,long nextId) { }
}
