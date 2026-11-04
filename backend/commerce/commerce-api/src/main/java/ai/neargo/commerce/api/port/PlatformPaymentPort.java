package ai.neargo.commerce.api.port;

/**
 * Platform collection only. Create is idempotent by tenant/paymentAttemptNo and immutable request;
 * recovery queries/replays that same attempt, never creates a replacement. Prepay issuance is
 * PENDING; SUCCEEDED requires authenticated matching recipient/amount/currency/paymentNo/paidAt.
 * Close may prove definitivelyClosed only with a durable closureReference fencing concurrent and
 * future create calls for the attempt. FAILED/NOT_FOUND alone never proves safe closure. Checkout
 * parameters contain only client-safe launch data, never host credentials or bearer tokens.
 */
public interface PlatformPaymentPort {
    PaymentOutcome create(PaymentIntentCommand command);

    PaymentOutcome query(PaymentQuery query);

    PaymentOutcome close(PaymentQuery query);

    PaymentRefundOutcome refund(PaymentRefundCommand command);

    PaymentRefundOutcome queryRefund(PaymentRefundQuery query);
}
