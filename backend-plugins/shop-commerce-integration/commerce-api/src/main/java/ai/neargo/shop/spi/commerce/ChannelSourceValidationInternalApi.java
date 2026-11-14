package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Source;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** This transport carries owner evidence only; it cannot grant financial or inventory authority. */
public interface ChannelSourceValidationInternalApi {
    String FOOD_VALIDATE_PATH = "/internal/food/commerce/sources/validate";
    String FOOD_CURRENT_PATH = "/internal/food/commerce/sources/current";

    @PostExchange(FOOD_CURRENT_PATH)
    Validation current(@RequestBody Source source);

    @PostExchange(FOOD_VALIDATE_PATH)
    Validation validate(@RequestBody Source source);
}
