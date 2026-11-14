package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.FoodKdsContracts.EventDraft;
import ai.neargo.shop.food.dto.FoodKdsContracts.GrantReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.ReplayVO;
import ai.neargo.shop.food.dto.FoodKdsContracts.RevokeReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.SubscriptionVO;

/**
 * Keeps the FoodKdsService business boundary scoped to original restaurant facts and lifecycle
 * authority.
 */
public interface FoodKdsService {
    SubscriptionVO grant(String entity, String store, String actor, GrantReq request, String key);

    SubscriptionVO revoke(
            String entity,
            String store,
            String actor,
            String subscription,
            RevokeReq request,
            String key);

    ReplayVO replay(
            String entity, String store, String actor, String subscription, long after, int limit);

    /** Only original durable business event consumers invoke this boundary. */
    void append(EventDraft event);
}
