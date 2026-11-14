package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.InitiatePaymentReq;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentAttemptView;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentLookup;

/**
 * Current identity, permanent original payment mapping and lock-free financial-owner orchestration.
 */
public interface CheckoutPaymentService {
    /** Initiate or recover the permanent original payment request for the real current buyer. */
    PaymentAttemptView initiatePayment(InitiatePaymentReq request);

    /**
     * Read original payment facts under current merchant scope without exposing payment parameters.
     */
    PaymentAttemptView findPayment(CheckoutScope scope, PaymentLookup request);

    /** Read original payment parameters only for the real current consumer session. */
    PaymentAttemptView findPaymentForCurrentBuyer(PaymentLookup request);
}
