package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort.Evidence;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort.PublishedCatalog;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort.Query;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Source-owner access stays internal; receipt IDs never become public merchant payment authority.
 */
public interface ChannelOrderSourceEvidenceInternalApi {
    String READ_PATH = "/internal/commerce/orders/source-evidence";
    String CATALOG_PATH = "/internal/commerce/orders/published-catalog";

    @PostExchange(READ_PATH)
    Evidence read(@RequestBody Query query);

    @PostExchange(CATALOG_PATH)
    PublishedCatalog publishedCatalog(@RequestBody Query query);
}
