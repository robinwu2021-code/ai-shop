package ai.neargo.shop.pay.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.PayGatewayRouter;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.service.RefundExecutionService;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.spi.settle.RefundDispatchResult.DispatchStatus;
import ai.neargo.shop.spi.settle.RefundRequest;
import ai.neargo.shop.spi.settle.SettlePort.PaymentReceipt;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;
import org.springframework.stereotype.Service;

/** External sends occur only after the financial claim commits; uncertain sends are only queried. */
@Service
public class RefundExecutionServiceImpl implements RefundExecutionService {
    private final RefundPaymentStore store;
    private final PayGatewayRouter gateways;
    private final UnsplitRefundAdjustment adjustment;

    public RefundExecutionServiceImpl(RefundPaymentStore store, PayGatewayRouter gateways,
                                      UnsplitRefundAdjustment adjustment) {
        this.store = store;
        this.gateways = gateways;
        this.adjustment = adjustment;
    }

    @Override
    public PaymentReceipt origin(String order, String trade, String channel) {
        StlPayment p = store.origin(order, trade, channel);
        return p == null ? null : new PaymentReceipt(p.getPaymentNo(), p.getOrderNo(), p.getUserNo(),
                p.getPayChannel(), p.getTradeNo(), p.getAmountMinor(), p.getCurrency(), p.getStatus());
    }

    @Override
    public boolean reverseUnsplit(SplitReversal request) {
        return adjustment.prepare(request, false);
    }

    @Override
    public boolean reverseUnsplit(SplitReversal request, boolean zeroLiability) {
        return adjustment.prepare(request, zeroLiability);
    }

    @Override
    public RefundDispatchResult request(RefundRequest request) {
        StlPayment row;
        try {
            row = store.prepare(request);
        } catch (BizException conflict) {
            if (conflict.errorCode() != ErrorCode.CONFLICT) {
                throw conflict;
            }
            StlPayment existing = store.byExecution(request.executionNo());
            return new RefundDispatchResult(request.executionNo(), existing == null ? null : existing.getPaymentNo(),
                    existing == null ? DispatchStatus.FAILED : DispatchStatus.UNKNOWN,
                    existing == null ? "REFUND_PREPARATION_CONFLICT" : "REFUND_EXECUTION_CONFLICT", false, null);
        }
        if (!store.claim(request.executionNo())) {
            return query(request);
        }
        if (row.getAmountMinor() == 0) {
            return store.completeZero(row.getRefundExecutionNo());
        }
        StlPayment origin = store.original(row.getOriginPaymentNo());
        try {
            if (origin == null) {
                return failed(row, "ORIGIN_PAYMENT_MISSING");
            }
            PayGateway gateway = gateways.of(row.getPayChannel());
            if (!gateway.supportsRefundCurrency(row.getCurrency())) {
                return failed(row, "REFUND_CURRENCY_UNSUPPORTED");
            }
            PayGateway.Result sent = gateway.refund(new PayGateway.TxContext(null, origin.getTradeNo(),
                    origin.getOutTradeNo(), origin.getAmountMinor(), row.getCurrency()), row.getAmountMinor(),
                    row.getOutTradeNo(), request.reason());
            if (sent == null || (!sent.success() && sent.retryable())) {
                return unknown(row, "REFUND_SEND_UNKNOWN");
            }
            if (!sent.success()) {
                return failed(row, "REFUND_REJECTED");
            }
            store.result(row.getRefundExecutionNo(), StlPayment.PENDING, null, sent.providerNo());
            return result(row, DispatchStatus.ACCEPTED, null, true);
        } catch (RuntimeException unknown) {
            return unknown(row, "REFUND_SEND_UNKNOWN");
        }
    }

    @Override
    public RefundDispatchResult confirm(String no, String channel, long amount, String currency, String provider) {
        return store.confirm(no, channel, amount, currency, provider);
    }

    @Override
    public RefundDispatchResult query(RefundRequest request) {
        StlPayment row = store.byExecution(request.executionNo());
        if (row != null && !RefundRequestIdentity.matches(row, store.original(request.originPaymentNo()), request)) {
            return result(row, DispatchStatus.UNKNOWN, "REFUND_EXECUTION_CONFLICT", false);
        }
        if (row != null && request.amountMinor() == 0 && StlPayment.PENDING.equals(row.getStatus())) {
            return store.completeZero(request.executionNo());
        }
        return query(request.executionNo());
    }

    @Override
    public RefundDispatchResult query(String executionNo) {
        StlPayment row = store.byExecution(executionNo);
        if (row == null) {
            return new RefundDispatchResult(executionNo, null, DispatchStatus.UNKNOWN,
                    "REFUND_NOT_PREPARED", true, null);
        }
        if (StlPayment.SUCCESS.equals(row.getStatus())) {
            return result(row, DispatchStatus.SUCCEEDED, null, false);
        }
        if (StlPayment.FAILED.equals(row.getStatus())) {
            return result(row, DispatchStatus.FAILED, row.getErrCode(), false);
        }
        if (StlPayment.INIT.equals(row.getStatus())) {
            return result(row, DispatchStatus.UNKNOWN, "REFUND_NOT_DISPATCHED", true);
        }
        if (Long.valueOf(0).equals(row.getAmountMinor())) {
            return result(row, DispatchStatus.UNKNOWN, "REFUND_ZERO_CONFIRMATION_REQUIRED", false);
        }
        try {
            PayGateway.QueryResult found = gateways.of(row.getPayChannel()).queryRefund(row.getOutTradeNo());
            if (found == null || !found.ok() || !found.found()) {
                return unknown(row, "REFUND_QUERY_UNKNOWN");
            }
            if (!found.paid()) {
                return result(row, DispatchStatus.ACCEPTED, null, true);
            }
            return confirm(row.getOutTradeNo(), row.getPayChannel(), found.amountMinor(),
                    found.currency(), found.tradeNo());
        } catch (RuntimeException unknown) {
            return unknown(row, "REFUND_QUERY_UNKNOWN");
        }
    }

    private RefundDispatchResult failed(StlPayment row, String error) {
        store.result(row.getRefundExecutionNo(), StlPayment.FAILED, error, null);
        return result(row, DispatchStatus.FAILED, error, false);
    }

    private RefundDispatchResult unknown(StlPayment row, String error) {
        store.result(row.getRefundExecutionNo(), StlPayment.PENDING, error, null);
        return result(row, DispatchStatus.UNKNOWN, error, true);
    }

    private static RefundDispatchResult result(StlPayment row, DispatchStatus status, String error, boolean retry) {
        return new RefundDispatchResult(row.getRefundExecutionNo(), row.getPaymentNo(), status, error,
                retry, row.getSucceededAt());
    }
}
