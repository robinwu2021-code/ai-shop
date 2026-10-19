package ai.neargo.shop.trade.order.contract;

import java.util.List;

/** Existing buyer filters stay orthogonal: contract status and fulfillment modes. */
public record StoreOrderQuery(String status, List<String> fulfillments) {
}
