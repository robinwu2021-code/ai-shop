package ai.neargo.shop.food.lifecycle;

import ai.neargo.shop.food.dto.ServiceSlotContracts.ClaimVO;

import java.math.BigDecimal;
import java.time.Instant;

/** Same-primary-transaction capacity ownership. Not a remote validation endpoint. */
public interface ServiceSlotLifecycle {
    void resizeSource(
            String entity, String store, String actor, String source, BigDecimal quantity);

    void assertScheduledCart(String entity, String store, String cart, Instant scheduledAt);

    ClaimVO reserveScheduledCart(
            String entity,
            String store,
            String actor,
            String cart,
            Instant scheduledAt,
            String key);

    void releaseCart(String entity, String store, String actor, String cart);

    void bindOrder(String entity, String store, String actor, String cart, String batch);

    void releaseOrder(String entity, String store, String actor, String batch);
}
