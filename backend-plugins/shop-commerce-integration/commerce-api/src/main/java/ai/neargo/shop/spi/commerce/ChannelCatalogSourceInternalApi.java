package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.CaptureCommand;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** The same owner capture contract is used by embedded and independent workers. */
public interface ChannelCatalogSourceInternalApi {
    String FOOD_CAPTURE_PATH = "/internal/food/commerce/catalog/capture";

    @PostExchange(FOOD_CAPTURE_PATH)
    Document capture(@RequestBody CaptureCommand command);
}
