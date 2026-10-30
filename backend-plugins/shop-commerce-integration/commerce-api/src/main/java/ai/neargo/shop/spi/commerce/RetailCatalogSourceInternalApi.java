package ai.neargo.shop.spi.commerce;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Original PLATFORM retail owner provides the existing generic source/document contracts privately.
 */
public interface RetailCatalogSourceInternalApi {
    String CURRENT_PATH = "/internal/retail/commerce/sources/current";
    String VALIDATE_PATH = "/internal/retail/commerce/sources/validate";
    String CAPTURE_PATH = "/internal/retail/commerce/catalog/capture";

    @PostExchange(CURRENT_PATH)
    ChannelSourceValidationPort.Validation current(
            @RequestBody ChannelSourceValidationPort.Source source);

    @PostExchange(VALIDATE_PATH)
    ChannelSourceValidationPort.Validation validate(
            @RequestBody ChannelSourceValidationPort.Source source);

    @PostExchange(CAPTURE_PATH)
    ChannelCatalogSourcePort.Document capture(
            @RequestBody ChannelCatalogSourcePort.CaptureCommand command);
}
