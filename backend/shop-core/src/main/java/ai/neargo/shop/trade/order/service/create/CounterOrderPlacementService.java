package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutResult;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PlaceCounterReq;

/**
 * Short Core roots surround durable intent changes; original stock IO runs only after releasing
 * them.
 */
public interface CounterOrderPlacementService {
    /** Current cashier authority is required for finalizing an accepted original. */
    CheckoutResult place(CheckoutScope scope, PlaceCounterReq request);

    /**
     * Machine recovery observes original stock without replaying a stored human acceptance actor.
     */
    int recoverPending(int limit);
}
