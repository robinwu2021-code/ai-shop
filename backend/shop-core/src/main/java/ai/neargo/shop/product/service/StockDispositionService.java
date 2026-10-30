package ai.neargo.shop.product.service;

import ai.neargo.shop.spi.product.StockDispositionPort.Confirmation;
import ai.neargo.shop.spi.product.StockDispositionPort.Disposition;
import ai.neargo.shop.spi.product.StockDispositionPort.Lookup;
import ai.neargo.shop.spi.product.StockDispositionPort.Reservation;
import ai.neargo.shop.spi.product.StockDispositionPort.ReservationLookup;
import ai.neargo.shop.spi.product.StockDispositionPort.ReservationView;
import ai.neargo.shop.spi.product.StockDispositionPort.ReleaseView;

/** Platform stock owner observes its original lock rows inside the platform transaction. */
public interface StockDispositionService {
    /** Reuse real platform locking while returning complete original reservation evidence. */
    ReservationView reserveOriginal(Reservation request);

    /** Release only complete matching unconfirmed rows through the existing platform owner. */
    ReleaseView releaseOriginal(ReservationLookup request);

    /** Durable held evidence keeps lost dispatch replies from producing a second reservation. */
    ReservationView observeReservation(ReservationLookup request);

    Disposition confirmOriginal(Confirmation command);

    Disposition observeOriginal(Lookup query);
}
