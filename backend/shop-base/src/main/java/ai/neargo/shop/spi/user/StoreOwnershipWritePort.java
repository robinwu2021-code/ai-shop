package ai.neargo.shop.spi.user;

/** Shared host ownership barrier. Both methods require the caller's actual write transaction. */
public interface StoreOwnershipWritePort {
    void lockAndCheck(String tenantNo, String expectedEntityNo, String storeNo);
    void lockEntityAndCheck(String tenantNo, String entityNo);
}
