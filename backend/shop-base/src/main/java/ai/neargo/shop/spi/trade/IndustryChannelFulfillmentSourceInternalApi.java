package ai.neargo.shop.spi.trade;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Exact original immutable source lookup; an external integration cannot authorize local
 * fulfillment.
 */
public interface IndustryChannelFulfillmentSourceInternalApi {
    String READ_PATH = "/internal/trade/channel-fulfillment-sources/read";

    @PostExchange(READ_PATH)
    IndustryChannelFulfillmentSourcePort.Facts read(
            @RequestBody IndustryChannelFulfillmentSourcePort.Query query);
}
