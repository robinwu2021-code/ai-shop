package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.MenuCatalogPriceContracts.Facts;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts.Query;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Original FOOD evidence uses the existing service-token transport, not a caller-supplied pricing
 * formula.
 */
public interface MenuCatalogPriceInternalApi {
    String CAPTURE_PATH = "/internal/food/menu-catalog-prices/capture";

    @PostExchange(CAPTURE_PATH)
    Facts capture(@RequestBody Query query);
}
