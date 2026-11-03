package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;

public record RefundCommand(StoreRef store, String orderNo, String refundNo, Money cashAmount, String reason) {}
