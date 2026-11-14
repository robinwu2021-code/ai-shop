package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningDeliveryContracts.DeliverySnapshot;
import ai.neargo.shop.food.dto.DiningDeliveryContracts.SaveReq;

/**
 * Keeps the DiningDeliveryService business boundary scoped to original restaurant facts and
 * lifecycle authority.
 */
public interface DiningDeliveryService {
    DeliverySnapshot saveStaff(
            String entity, String store, String actor, String cart, SaveReq request, String key);

    DeliverySnapshot saveCustomer(String cart, CartAccess access, SaveReq request, String key);

    DeliverySnapshot getStaff(String entity, String store, String cart);

    DeliverySnapshot getCustomer(String cart, CartAccess access);

    /** Trusted source-fact read, not caller authorization. Rejects a non-DELIVERY original cart. */
    DeliverySnapshot get(String entity, String store, String cart);
}
