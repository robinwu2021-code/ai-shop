package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.service.DiningChannelFulfillmentSignalPort;
import ai.neargo.shop.spi.food.DiningServiceEvent;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Signed SYSTEM access reads the original native event and its exact committed custody; it never
 * mutates fulfillment.
 */
public interface DiningChannelFulfillmentSignalInternalApi {
    String PATH = "/internal/food/channel-fulfillment/signal";

    @PostExchange(PATH)
    DiningChannelFulfillmentSignalPort.Signal resolve(
            @RequestBody DiningServiceEvent originalEvent);
}
