package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Facts;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.IdentityQuery;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Query;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Trusted transport exposes original authority evidence without exposing authorization material.
 */
public interface ChannelCatalogAuthorityInternalApi {
    String CAPTURE_PATH = "/internal/commerce/catalog-authorities/capture";

    String RESOLVE_PATH = "/internal/commerce/catalog-authorities/resolve";

    @PostExchange(RESOLVE_PATH)
    Facts resolve(@RequestBody IdentityQuery query);

    @PostExchange(CAPTURE_PATH)
    Facts capture(@RequestBody Query query);
}
