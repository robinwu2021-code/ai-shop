package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Facts;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Query;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Trusted transport exposes original authority evidence without exposing authorization material.
 */
public interface ChannelCatalogAuthorityInternalApi {
    String CAPTURE_PATH = "/internal/commerce/catalog-authorities/capture";

    @PostExchange(CAPTURE_PATH)
    Facts capture(@RequestBody Query query);
}
