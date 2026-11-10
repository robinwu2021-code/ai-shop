package ai.neargo.shop.spi.user;

/** Read-only host-owned history. Does not prove absence of Pay/commerce history or provide a write fence. */
public interface StoreRelinkHostFactsPort {
    record Facts(boolean hasTrades, boolean pendingApply) {}
    Facts inspect(String tenantNo, String entityNo, String storeNo);
}
