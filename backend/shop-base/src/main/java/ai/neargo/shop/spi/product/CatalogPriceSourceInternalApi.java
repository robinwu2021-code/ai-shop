package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Facts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Request;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Service-token transport for original product/tax evidence; no raw price or market selection can
 * be supplied.
 */
public interface CatalogPriceSourceInternalApi {
    String CAPTURE_PATH = "/internal/product/catalog-price-sources/capture";

    @PostExchange(CAPTURE_PATH)
    Facts capture(@RequestBody Request request);
}
