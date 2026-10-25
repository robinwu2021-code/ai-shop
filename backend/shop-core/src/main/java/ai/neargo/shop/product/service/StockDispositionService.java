package ai.neargo.shop.product.service;

import ai.neargo.shop.spi.product.StockDispositionPort.Confirmation;
import ai.neargo.shop.spi.product.StockDispositionPort.Disposition;
import ai.neargo.shop.spi.product.StockDispositionPort.Lookup;

/** Platform stock owner observes its original lock rows inside the platform transaction. */
public interface StockDispositionService {
    Disposition confirmOriginal(Confirmation command);

    Disposition observeOriginal(Lookup query);
}
