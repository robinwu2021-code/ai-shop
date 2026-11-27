package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.StoreRef;

/** Trusted host operating admission. A separate platform store requires committed commerce component facts. */
@FunctionalInterface
public interface PaidStoreActivationPort {
    default boolean separateDatabase() { return false; }

    void activate(StoreRef store, String entityNo, String orderNo, long purchasedAt, long at);
}
