package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;

public record OrderQuery(StoreRef store, String orderNo) {}
