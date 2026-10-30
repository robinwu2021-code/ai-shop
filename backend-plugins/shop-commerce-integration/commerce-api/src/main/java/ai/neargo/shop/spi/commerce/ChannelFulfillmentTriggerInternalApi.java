package ai.neargo.shop.spi.commerce;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Private SYSTEM transport carries original references only; durable ACK does not assert supplier
 * success.
 */
public interface ChannelFulfillmentTriggerInternalApi {
    String PATH = "/internal/commerce/fulfillments/trigger";

    @PostExchange(PATH)
    ChannelFulfillmentTriggerPort.Result record(
            @RequestBody ChannelFulfillmentTriggerPort.Request request);
}
