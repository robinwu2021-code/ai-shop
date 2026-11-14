package ai.neargo.shop.inventory.service;

import ai.neargo.shop.spi.product.StockDispositionPort.Confirmation;
import ai.neargo.shop.spi.product.StockDispositionPort.Disposition;
import ai.neargo.shop.spi.product.StockDispositionPort.Lookup;
import ai.neargo.shop.spi.product.StockDispositionPort.ReservationLookup;
import ai.neargo.shop.spi.product.StockDispositionPort.ReservationView;
import ai.neargo.shop.spi.product.StockDispositionPort.ReleaseView;

/**
 * Inventory's original reservation owner; uses invTransactionManager rather than platform mappings.
 */
public interface ReservationDispositionService {
    /**
     * The actual original head owner and external reference survive current SKU mapping changes.
     */
    ReleaseView releaseOriginal(ReservationLookup request);

    /** Only complete matching actual owner rows authorize common-order finalization. */
    ReservationView observeReservation(ReservationLookup request);

    Disposition confirmOriginal(Confirmation command);

    Disposition observeOriginal(Lookup query);
}
