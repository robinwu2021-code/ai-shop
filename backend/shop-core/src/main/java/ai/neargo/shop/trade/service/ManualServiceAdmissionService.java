package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PrepareCounterReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCheckoutQuote;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.AcceptCounterReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterConfirmation;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PlaceCounterReq;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutResult;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutQuote;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.PrepareCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.SourceLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ConfirmManualReceipt;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualAdmissionView;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualSourceFacts;

/** Local Core owns receipt durability and recovery; remote callers cannot manufacture approval. */
public interface ManualServiceAdmissionService {
    /** Shared counter quote keeps actual party identity separate from purchase acceptance. */
    CounterCheckoutQuote prepareCounter(CheckoutScope scope, PrepareCounterReq request);

    /** Read the immutable original displayed quote under current merchant scope. */
    CounterCheckoutQuote readCounterQuote(CheckoutScope scope, OperationRef operation);

    /** Explicit current cashier acceptance is required independently from member identity. */
    CounterConfirmation acceptCounter(CheckoutScope scope, AcceptCounterReq request);

    /** Finalize only the accepted original through common stock and persistence owners. */
    CheckoutResult placeCounter(CheckoutScope scope, PlaceCounterReq request);

    CheckoutQuote prepare(CheckoutScope scope, PrepareCheckoutReq request);

    ManualAdmissionView confirm(CheckoutScope scope, ConfirmManualReceipt request);

    ManualAdmissionView query(CheckoutScope scope, ManualLookup request);

    ManualSourceFacts source(CheckoutScope scope, SourceLookup request);

    /** Observe/retry only the same original pending stock operation, bounded to 1..100 rows. */
    int recoverPending(int limit);

    /** The public route's owner number is checked before a receipt can have any effect. */
    ManualAdmissionView confirmForOrder(
            CheckoutScope scope, String orderNo, ConfirmManualReceipt request);

    /** Public recovery cannot cross an operation's original common order. */
    ManualAdmissionView queryForOrder(CheckoutScope scope, String orderNo, ManualLookup request);

    /** Public observation cannot cross an operation's original common order. */
    ManualSourceFacts sourceForOrder(CheckoutScope scope, String orderNo, SourceLookup request);
}
