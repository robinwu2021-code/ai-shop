package ai.neargo.commerce.core.spi;

import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.subscription.StoreRelinkFacade;

public interface StoreRelinkRepository {
    StoreRelinkFacade.Facts inspect(StoreRef store);
    void requireOuterTransaction(StoreRef store);
    void lockAndRelink(StoreRelinkFacade.Command command);
}
