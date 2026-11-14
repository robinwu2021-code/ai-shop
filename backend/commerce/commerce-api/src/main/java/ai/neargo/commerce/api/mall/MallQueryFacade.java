package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;

import java.util.List;

/** Current authorized store reads. Recovery mutation remains on trusted coordinator ports. */
public interface MallQueryFacade {
    List<MallOrderDetailView.Summary> orders(
            ActorContext actor, StoreRef store, String afterOrderNo, int limit);

    MallOrderDetailView order(ActorContext actor, OrderQuery query);
}
