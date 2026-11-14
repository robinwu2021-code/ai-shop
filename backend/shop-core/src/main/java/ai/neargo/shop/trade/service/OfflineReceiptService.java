package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentAttemptView;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentLookup;
import ai.neargo.shop.spi.trade.OfflineReceiptPort.OfflineReceiptReq;

/** Trade owner authorization and permanent original offline request mapping. */
public interface OfflineReceiptService {
    /** Accept a real current cashier's full original receipt. */
    PaymentAttemptView confirm(CheckoutScope scope, OfflineReceiptReq request);

    /** Read existing facts through the current merchant's authorized store scope. */
    PaymentAttemptView find(CheckoutScope scope, PaymentLookup request);
}
