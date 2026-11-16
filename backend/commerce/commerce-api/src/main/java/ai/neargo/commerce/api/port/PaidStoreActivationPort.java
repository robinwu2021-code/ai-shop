package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.StoreRef;

/** Trusted host operating admission. Joins the identical local fulfillment transaction. */
@FunctionalInterface
public interface PaidStoreActivationPort {
    void activate(StoreRef store, String entityNo, String orderNo, long purchasedAt, long at);
}
