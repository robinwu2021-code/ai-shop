package ai.neargo.shop.spi.trade;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Read-only original decision evidence; external channel processes cannot create local merchant
 * decisions.
 */
public interface IndustryChannelAfterSaleInternalApi {
    String READ_PATH = "/internal/trade/channel-after-sales/read";

    @PostExchange(READ_PATH)
    IndustryChannelAfterSalePort.Facts read(@RequestBody IndustryChannelAfterSalePort.Query query);
}
