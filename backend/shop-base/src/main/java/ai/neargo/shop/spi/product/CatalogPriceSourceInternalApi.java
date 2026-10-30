package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Facts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketScope;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Request;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Service-token transport for original product/tax evidence; no raw price or market selection can
 * be supplied.
 */
public interface CatalogPriceSourceInternalApi {
    String CAPTURE_PATH = "/internal/product/catalog-price-sources/capture";

    String SCOPE_PATH = "/internal/product/catalog-price-sources/scope";

    @PostExchange(SCOPE_PATH)
    CatalogPriceSourcePort.ScopeFacts scope(@RequestBody MarketScope scope);

    String MARKET_PATH = "/internal/product/catalog-price-sources/market";

    @PostExchange(MARKET_PATH)
    MarketFacts market(@RequestBody MarketScope scope);

    @PostExchange(CAPTURE_PATH)
    Facts capture(@RequestBody Request request);
}
