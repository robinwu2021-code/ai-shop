package ai.neargo.shop.food.api;

import ai.neargo.shop.auth.StoreRequestScopeValidator;
import java.util.Set;

/** Keeps the restaurant entry contract while sharing its fact comparison with retail and beauty. */
public final class FoodRequestScopeValidator {
    /** Trusted arguments come from existing authentication and the resource owner's facts. */
    public void requireStoreTarget(String trustedEntityNo, String trustedStoreNo, String targetEntityNo,
            String targetStoreNo, String requestedStoreNo, Set<String> authorizedStoreNos) {
        StoreRequestScopeValidator.requireStoreTarget(trustedEntityNo, trustedStoreNo, targetEntityNo,
                targetStoreNo, requestedStoreNo, authorizedStoreNos);
    }
}
