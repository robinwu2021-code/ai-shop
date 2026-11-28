package ai.neargo.shop.spi.user;

import java.util.function.Supplier;

/** Shared host ownership barrier used by business writers and store claims. */
public interface StoreOwnershipWritePort {
    /** Scope a trade write that owns its transaction, keeping platform ownership locked through completion. */
    default <T> T withStoreLock(String tenantNo, String expectedEntityNo, String storeNo, Supplier<T> write) {
        throw new UnsupportedOperationException("Scoped store ownership lock unavailable");
    }

    void lockAndCheck(String tenantNo, String expectedEntityNo, String storeNo);
    void lockEntityAndCheck(String tenantNo, String entityNo);
    default void lockEntityExclusiveAndCheck(String tenantNo,String entityNo){throw new UnsupportedOperationException("Exclusive entity ownership lock unavailable");}
}
