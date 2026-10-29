package ai.neargo.shop.retail.commerce.port;

import ai.neargo.shop.retail.commerce.dto.RetailSelection;
import ai.neargo.shop.spi.commerce.CatalogAvailability;

import java.time.Instant;
import java.util.List;

/**
 * Original retail store calendar adapter must contribute real windows; absence cannot mean always
 * open.
 */
public interface RetailCatalogAvailabilityPort {
    Facts capture(String entityNo, String storeNo, List<RetailSelection> selections);

    record Facts(
            String entityNo,
            String storeNo,
            CatalogAvailability availability,
            String sourceRevision,
            Instant verifiedAt,
            Instant validUntil,
            String receiptNo) {}
}
