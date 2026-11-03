package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

/** Platform collection only. UNKNOWN is reconciled by querying the same intent; never create a replacement blindly. */
public interface PlatformPaymentPort {
 PaymentOutcome create(PaymentIntentCommand command);
  PaymentOutcome query(PaymentQuery query);
  PaymentOutcome close(PaymentQuery query);
  PaymentRefundOutcome refund(PaymentRefundCommand command);
  PaymentRefundOutcome queryRefund(PaymentRefundQuery query);

}
