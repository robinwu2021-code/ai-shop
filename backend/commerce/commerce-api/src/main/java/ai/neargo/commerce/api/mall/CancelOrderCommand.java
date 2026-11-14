package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;

public record CancelOrderCommand(StoreRef store, String orderNo, String operationNo, String reason) {}
