package ai.neargo.shop.trade.order.service;

import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.command.ShipCommand;
import ai.neargo.shop.trade.order.command.ShipResult;

/** Production shipping command boundary; all facts are committed in the primary transaction. */
public interface OrderShippingService {
    /** Serialize retries with eligibility checks so a lost response cannot create another effective package. */
    ShipResult ship(String subOrderNo, ShipCommand command, OrderActor actor);
}
