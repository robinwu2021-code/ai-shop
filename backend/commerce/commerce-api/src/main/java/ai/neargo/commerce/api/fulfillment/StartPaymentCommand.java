package ai.neargo.commerce.api.fulfillment;

import ai.neargo.commerce.api.common.StoreRef;

/**
 * Client selects allowed channel/method only; monetary/recipient/buyer facts come from the order.
 */
public record StartPaymentCommand(
        StoreRef store, String orderNo, String channel, String payMethod, String payerReference) {}
