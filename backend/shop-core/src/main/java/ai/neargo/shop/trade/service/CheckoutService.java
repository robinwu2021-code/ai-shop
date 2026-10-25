package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.CheckoutPort.BuyerConfirmation;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutQuote;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutResult;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutSourceFacts;
import ai.neargo.shop.spi.trade.CheckoutPort.ConfirmCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.InitiatePaymentReq;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentAttemptView;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentLookup;
import ai.neargo.shop.spi.trade.CheckoutPort.PlaceCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.PrepareCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.SourceLookup;

/** Local owner API; consumer reads never accept merchant scope. */
public interface CheckoutService {
    /** Initiate or recover the permanent original payment request for the real current buyer. */
    PaymentAttemptView initiatePayment(InitiatePaymentReq request);

    /**
     * Read original payment facts under current merchant scope without exposing payment parameters.
     */
    PaymentAttemptView findPayment(CheckoutScope scope, PaymentLookup request);

    /** Read the current buyer's original payment and parameters without initiating another send. */
    PaymentAttemptView findPaymentForCurrentBuyer(PaymentLookup request);

    /**
     * Resolve a strict reference and freeze the existing writer price after current merchant
     * authorization.
     */
    CheckoutQuote prepare(CheckoutScope scope, PrepareCheckoutReq request);

    /**
     * Dedicated owner operation persists manual mode; ordinary OFFLINE requests cannot select it.
     */
    CheckoutQuote prepareManual(CheckoutScope scope, PrepareCheckoutReq request);

    /** Read original quote evidence through the current merchant permission and data scope. */
    CheckoutQuote readQuote(CheckoutScope scope, OperationRef operation);

    /** Bind the real consumer session to exactly the immutable quote being purchased. */
    BuyerConfirmation confirm(ConfirmCheckoutReq request);

    /**
     * Place once through the original writer, preserving the first external key across recovery.
     */
    CheckoutResult place(PlaceCheckoutReq request);

    /** Recover original durable state without rebuilding orders or relying on cached responses. */
    CheckoutResult findOperation(CheckoutScope scope, OperationRef operation);

    /** Read one consistent owner snapshot and serialize its revision for later source binding. */
    CheckoutSourceFacts source(CheckoutScope scope, SourceLookup request);

    /**
     * Resolve operation by actual current buyer before internal scope mapping; C cannot supply B
     * scope.
     */
    CheckoutQuote readQuoteForCurrentBuyer(OperationRef operation);
}
