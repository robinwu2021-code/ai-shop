package ai.neargo.shop.pay.service.impl;

import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.spi.settle.RefundRequest;
import java.util.Objects;

/** Both local success and external queries require the same frozen instruction and original cash receipt. */
final class RefundRequestIdentity {
    private RefundRequestIdentity() { }

    static boolean matches(StlPayment row, StlPayment origin, RefundRequest request) {
        return origin != null && StlPayment.SUCCESS.equals(origin.getStatus())
                && StlPayment.PAY.equals(origin.getDirection()) && StlPayment.REFUND.equals(row.getDirection())
                && Objects.equals(row.getRefundExecutionNo(), request.executionNo())
                && Objects.equals(row.getOriginPaymentNo(), request.originPaymentNo())
                && Objects.equals(origin.getPaymentNo(), request.originPaymentNo())
                && Objects.equals(row.getAfterSaleNo(), request.afterSaleNo())
                && Objects.equals(row.getSubOrderNo(), request.subOrderNo())
                && Objects.equals(row.getAmountMinor(), request.amountMinor())
                && Objects.equals(row.getCurrency(), request.currency())
                && Objects.equals(row.getCurrency(), origin.getCurrency())
                && Objects.equals(row.getOrderNo(), origin.getOrderNo())
                && Objects.equals(row.getUserNo(), origin.getUserNo())
                && Objects.equals(row.getPayChannel(), origin.getPayChannel());
    }
}
