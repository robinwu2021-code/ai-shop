package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.FoodKdsContracts.GrantReq;
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

    /**
     * 核验这台屏的订阅仍然有效（本人、生效中、未过期、设备仍在册），返回它看的工位。
     * 屏收到刷新后按工位调后厨任务列表拉当前状态（TDD-餐饮域迁移新库 §2.8 D-3）。
     */
    SubscriptionVO authorize(String entity, String store, String actor, String subscription);
}
