package ai.neargo.shop.inventory.service;

import ai.neargo.shop.spi.product.StockDispositionPort.Confirmation;
import ai.neargo.shop.spi.product.StockDispositionPort.Disposition;
import ai.neargo.shop.spi.product.StockDispositionPort.Lookup;

/**
 * Inventory's original reservation owner; uses invTransactionManager rather than platform mappings.
 */
public interface ReservationDispositionService {
    Disposition confirmOriginal(Confirmation command);

    Disposition observeOriginal(Lookup query);
}
