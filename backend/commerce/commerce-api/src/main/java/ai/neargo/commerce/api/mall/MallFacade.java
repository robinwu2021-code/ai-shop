package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;

public interface MallFacade {
 QuoteView quote(ActorContext actor, QuoteCommand command);
  OrderView placeOrder(ActorContext actor, PlaceOrderCommand command);
  OrderView order(ActorContext actor, OrderQuery query);
  OrderView cancelOrder(ActorContext actor, CancelOrderCommand command);
  QuoteView quoteUpgrade(ActorContext actor, UpgradeCommand command);
  RefundView refund(ActorContext actor, RefundCommand command);

}
