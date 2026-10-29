package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.DiningChannelFulfillmentFacts;

/** Read-only original accepted CHANNEL/DELIVERY custody; no core delivery or provider writes. */
public interface DiningChannelFulfillmentService {
    DiningChannelFulfillmentFacts.Facts capture(DiningChannelFulfillmentFacts.Query query);
}
