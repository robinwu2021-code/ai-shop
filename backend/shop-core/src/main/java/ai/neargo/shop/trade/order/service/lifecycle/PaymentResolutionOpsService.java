package ai.neargo.shop.trade.order.service.lifecycle;

import java.util.List;

/** Human handling of original order-payment evidence, independent of order restoration. */
public interface PaymentResolutionOpsService {
    List<Case> list(String status);
    Case refund(String payNo, String paymentNo);

    record Case(String payNo, String paymentNo, String reason, String status, long amountMinor,
                String currency, String refundNo, String refundErrorCode, boolean canRefund) { }
}
