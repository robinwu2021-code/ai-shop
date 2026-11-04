package ai.neargo.commerce.core.spi;

import ai.neargo.commerce.api.common.StoreRef;

public interface SubscriptionAccountRepository {
    long readEntitlementVersion(StoreRef store);

    AccountSnapshot lockSubscriptionAccount(StoreRef store);
}
