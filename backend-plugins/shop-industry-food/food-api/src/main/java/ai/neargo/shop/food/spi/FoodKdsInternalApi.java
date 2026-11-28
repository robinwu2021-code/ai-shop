package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.FoodKdsContracts.GrantReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.RevokeReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.SubscriptionVO;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Keeps the FoodKdsInternalApi protocol scoped to original restaurant facts and lifecycle
 * authority.
 */
public interface FoodKdsInternalApi {
    String ROOT = "/internal/food/kds";

    record Grant(
            String entityNo, String storeNo, String actorNo, GrantReq definition, String key) {}

    record Revoke(
            String entityNo,
            String storeNo,
            String actorNo,
            String subscriptionNo,
            RevokeReq definition,
            String key) {}

    record Authorize(String entityNo, String storeNo, String actorNo, String subscriptionNo) {}

    @PostExchange(ROOT + "/grant")
    SubscriptionVO grant(@RequestBody Grant request);

    @PostExchange(ROOT + "/revoke")
    SubscriptionVO revoke(@RequestBody Revoke request);

    @PostExchange(ROOT + "/authorize")
    SubscriptionVO authorize(@RequestBody Authorize request);
}
