package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort.Facts;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort.Query;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Original committed FOOD custody stays behind the existing internal token and signed scope
 * boundary.
 */
public interface ChannelFulfillmentSourceInternalApi {
    String FOOD_CAPTURE_PATH = "/internal/food/channel-fulfillment/capture";

    @PostExchange(FOOD_CAPTURE_PATH)
    Facts capture(@RequestBody Query query);
}
