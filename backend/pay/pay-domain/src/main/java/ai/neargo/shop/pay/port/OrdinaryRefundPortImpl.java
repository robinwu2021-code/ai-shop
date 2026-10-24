package ai.neargo.shop.pay.port;

import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.service.RefundExecutionService;
import ai.neargo.shop.spi.settle.OrdinaryRefundPort;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import java.util.Objects;

/** Exact cash owner checks precede financial execution; source approval stays with the source owner. */
public class OrdinaryRefundPortImpl implements OrdinaryRefundPort {
    private final PaymentLedgerService ledger;
    private final RefundExecutionService refunds;

    public OrdinaryRefundPortImpl(PaymentLedgerService ledger, RefundExecutionService refunds) {
        this.ledger = ledger;
        this.refunds = refunds;
    }

    @Override
    public Receipt request(Execution execution) {
        validate(execution);
        return receipt(execution, refunds.request(execution.refund()));
    }

    @Override
    public Receipt query(Execution execution) {
        validate(execution);
        return receipt(execution, refunds.query(execution.refund()));
    }

    private void validate(Execution execution) {
        if (execution == null || execution.refund() == null) {
            throw new IllegalArgumentException("PAY_REFUND_REFERENCE_INVALID");
        }
        require(execution.orderNo());
        require(execution.userNo());
        require(execution.originOutTradeNo());
        var frozen = execution.refund();
        require(frozen.executionNo());
        require(frozen.afterSaleNo());
        require(frozen.subOrderNo());
        require(frozen.originPaymentNo());
        require(frozen.currency());
        var original = ledger.ordinaryPayment(execution.originOutTradeNo());
        if (original == null || !Objects.equals(original.outTradeNo(), execution.originOutTradeNo())
                || !Objects.equals(original.paymentNo(), frozen.originPaymentNo())
                || !Objects.equals(original.orderNo(), execution.orderNo())
                || !Objects.equals(original.userNo(), execution.userNo())
                || !"SUCCESS".equals(original.status())
                || !Objects.equals(original.currency(), frozen.currency())
                || original.amountMinor() == null || frozen.amountMinor() < 0
                || frozen.amountMinor() > original.amountMinor()) {
            throw new IllegalArgumentException("PAY_REFUND_ORIGIN_CONFLICT");
        }
    }

    private static Receipt receipt(Execution execution, RefundDispatchResult result) {
        if (result == null || result.status() == null
                || !Objects.equals(execution.refund().executionNo(), result.executionNo())) {
            throw new IllegalStateException("PAY_REFUND_EXECUTION_RECEIPT_CONFLICT");
        }
        return new Receipt(execution, result);
    }

    private static void require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("PAY_REFUND_REFERENCE_INVALID");
        }
    }
}
