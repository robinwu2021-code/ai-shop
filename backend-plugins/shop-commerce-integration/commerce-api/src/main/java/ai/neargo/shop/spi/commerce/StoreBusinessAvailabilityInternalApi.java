package ai.neargo.shop.spi.commerce;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Fixed FOOD/RETAIL original owner transport carries business interpretation and source identity,
 * never a caller-reported state.
 */
public interface StoreBusinessAvailabilityInternalApi {
    @PostExchange("/internal/{owner}/commerce/store-availability/capture")
    ChannelAvailabilitySourcePort.Facts capture(
            @PathVariable("owner") String owner,
            @RequestBody ChannelAvailabilitySourcePort.Query query);
}
