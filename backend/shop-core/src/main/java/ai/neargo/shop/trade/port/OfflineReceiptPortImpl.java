package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentAttemptView;
import ai.neargo.shop.spi.trade.CheckoutPort.PaymentLookup;
import ai.neargo.shop.spi.trade.OfflineReceiptPort;
import ai.neargo.shop.trade.service.OfflineReceiptService;

import org.springframework.stereotype.Component;

/** Single production adapter; no transport path can replace the actual current cashier check. */
@Component
public class OfflineReceiptPortImpl implements OfflineReceiptPort {
    private final OfflineReceiptService receipts;

    /** Keep authorization and durable trade facts in the trade owner. */
    public OfflineReceiptPortImpl(OfflineReceiptService receipts) {
        this.receipts = receipts;
    }

    @Override
    public PaymentAttemptView confirm(CheckoutScope scope, OfflineReceiptReq request) {
        return receipts.confirm(scope, request);
    }

    @Override
    public PaymentAttemptView find(CheckoutScope scope, PaymentLookup request) {
        return receipts.find(scope, request);
    }
}
