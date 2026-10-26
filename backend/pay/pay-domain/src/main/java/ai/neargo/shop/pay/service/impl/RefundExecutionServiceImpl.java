package ai.neargo.shop.pay.service.impl;

import ai.neargo.shop.pay.acquiring.service.RefundService;
import org.springframework.beans.factory.ObjectProvider;
import ai.neargo.shop.pay.numbering.PayOperationsDocuments;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.PayGatewayRouter;
import ai.neargo.shop.pay.channel.legacy.LegacyChannelBridge;
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
    @org.springframework.beans.factory.annotation.Autowired
    private PayOperationsDocuments documents;
    /** 存量交易没落子商户号时，从它的请求快照取（F08 · Review R08） */
    private ai.neargo.shop.pay.service.PaymentLedgerService ledger;
    private final RefundPaymentStore store;
    private final RefundCapacityLedger capacity;
    private final PayGatewayRouter gateways;
    private final UnsplitRefundAdjustment adjustment;

    public RefundExecutionServiceImpl(RefundPaymentStore store, PayGatewayRouter gateways,
                                      UnsplitRefundAdjustment adjustment, RefundCapacityLedger capacity) {
        this.capacity = capacity;
        this.store = store;
        this.gateways = gateways;
        this.adjustment = adjustment;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setLedger(ai.neargo.shop.pay.service.PaymentLedgerService ledger) {
        this.ledger = ledger;
    }

    /** 渠道管线（TDD-支付服务-I2 S4）；手工构造的单测里没有，直调原网关 */
    private LegacyChannelBridge channels;

    /** 退款单（I3 S6）；手工构造的单测与切片测试里没有 —— 调用时再取 */
    private ObjectProvider<RefundService> refundOrderProvider;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRefundOrders(ObjectProvider<RefundService> refundOrders) {
        this.refundOrderProvider = refundOrders;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setChannels(LegacyChannelBridge channels) {
        this.channels = channels;
    }

    /**
     * 原交易实际进的子商户号。新交易落在行上；存量行为空时从它的请求快照按下单时同一规则取（F08 · Review R08）。
     * 此前恒传 null：服务商 / 子商户模式下通道找不到原交易，退款失败而用户的钱已经扣了。
     */
    private String subMerchantOf(StlPayment origin) {
        if (origin.getSubMerchantId() != null && !origin.getSubMerchantId().isBlank()) {
            return origin.getSubMerchantId();
        }
        if (ledger == null || origin.getOutTradeNo() == null) {
            return null;
        }
        return ledger.paymentRequest(origin.getOutTradeNo())
                .flatMap(DirectAccounts::of)
                .map(r -> r.subMerchantId())
                .orElse(null);
    }

    @Override
    public PaymentReceipt origin(String order, String trade, String channel) {
        StlPayment p = store.origin(order, trade, channel);
        return p == null ? null : new PaymentReceipt(p.getPaymentNo(), p.getOrderNo(), p.getUserNo(),
                p.getPayChannel(), p.getTradeNo(), p.getAmountMinor(), p.getCurrency(), p.getStatus()).withDocumentNo(documents.find(DocumentType.PAYMENT_ATTEMPT, "MAIN", p.getPaymentNo()));
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
        RefundService refundOrders = refundOrderProvider == null ? null : refundOrderProvider.getIfAvailable();
        if (refundOrders != null && !refundOrders.dispatchAllowed(request.executionNo())) {
            // 退款单待复核（I3 S6）：不发渠道。可重试 —— 复核通过后售后重试 / 调用方再调一次即执行
            return new RefundDispatchResult(request.executionNo(), row.getPaymentNo(), DispatchStatus.UNKNOWN,
                    "REFUND_REVIEW_PENDING", true, null);
        }
        if (!store.claim(request.executionNo())) {
            return query(request);
        }
        if (refundOrders != null) {
            refundOrders.dispatched(request.executionNo());
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
            var ctx = new PayGateway.TxContext(subMerchantOf(origin), origin.getTradeNo(),
                    origin.getOutTradeNo(), origin.getAmountMinor(), row.getCurrency());
            PayGateway.Result sent = channels == null
                    ? gateway.refund(ctx, row.getAmountMinor(), row.getOutTradeNo(), request.reason())
                    : channels.refund(row.getPayChannel(), ctx, row.getAmountMinor(), row.getOutTradeNo(), request.reason());
            if (sent == null || (!sent.success() && sent.retryable())) {
                return unknown(row, "REFUND_SEND_UNKNOWN");
            }
            if (!sent.success()) {
                return sent.definitiveRejection() ? failed(row, "REFUND_REJECTED")
                        : unknown(row, "REFUND_SEND_UNPROVEN");
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
        var safety = capacity.safety(row);
        if ("REFUND_LATE_SUCCESS_FROZEN".equals(safety.errorCode())
                || "REFUND_QUERY_COORDINATES_REQUIRED".equals(safety.errorCode()))
            return result(row, DispatchStatus.UNKNOWN, safety.errorCode(), false);
        if (StlPayment.SUCCESS.equals(row.getStatus())) {
            return result(row, DispatchStatus.SUCCEEDED, null, false);
        }
        if (safety.terminalVerified()) {
            return result(row, DispatchStatus.FAILED, row.getErrCode(), false);
        }
        if (StlPayment.INIT.equals(row.getStatus())) {
            if(safety.outcome()==ai.neargo.shop.spi.pay.RefundQueryResult.Status.MANUAL_REQUIRED)
                return result(row,DispatchStatus.UNKNOWN,safety.errorCode(),false);
            if(safety.outcome()!=ai.neargo.shop.spi.pay.RefundQueryResult.Status.PROCESSING)
                return result(row, DispatchStatus.UNKNOWN, "REFUND_NOT_DISPATCHED", true);
        }
        if (Long.valueOf(0).equals(row.getAmountMinor())) {
            return result(row, DispatchStatus.UNKNOWN, "REFUND_ZERO_CONFIRMATION_REQUIRED", false);
        }
        try {
            var found = gateways.of(row.getPayChannel()).queryRefundOutcome(row.getOutTradeNo());
            if (found == null) return unknown(row, "REFUND_QUERY_UNKNOWN");
            if (!row.getOutTradeNo().equals(found.outRefundNo()))
                return result(row, DispatchStatus.UNKNOWN, "REFUND_QUERY_IDENTITY_MISMATCH", false);
            if (found.status() == ai.neargo.shop.spi.pay.RefundQueryResult.Status.SUCCEEDED)
                return confirm(row.getOutTradeNo(), row.getPayChannel(), found.amountMinor(),
                        found.currency(), found.providerNo());
            capacity.observe(row.getPaymentNo(), found);
            var current = capacity.safety(store.byExecution(executionNo));
            if (current.outcome() == ai.neargo.shop.spi.pay.RefundQueryResult.Status.MANUAL_REQUIRED)
                return result(row, DispatchStatus.UNKNOWN, current.errorCode(), false);
            return switch (found.status()) {
                case TERMINAL_FAILED -> result(row, DispatchStatus.FAILED, "REFUND_TERMINAL_CLOSED", false);
                case PROCESSING -> result(row, DispatchStatus.ACCEPTED, null, true);
                case MANUAL_REQUIRED -> result(row, DispatchStatus.UNKNOWN, "REFUND_MANUAL_REQUIRED", false);
                default -> unknown(row, "REFUND_QUERY_UNKNOWN");
            };
        } catch (RuntimeException unknown) {
            return unknown(row, "REFUND_QUERY_UNKNOWN");
        }
    }

    private RefundDispatchResult failed(StlPayment row, String error) {
        store.reject(row.getRefundExecutionNo(), error);
        return result(row, DispatchStatus.FAILED, error, false);
    }

    private RefundDispatchResult unknown(StlPayment row, String error) {
        var safety=capacity.safety(store.byExecution(row.getRefundExecutionNo()));
        if(safety!=null && safety.outcome()==ai.neargo.shop.spi.pay.RefundQueryResult.Status.MANUAL_REQUIRED)
            return result(row,DispatchStatus.UNKNOWN,safety.errorCode(),false);
        store.result(row.getRefundExecutionNo(), StlPayment.PENDING, error, null);
        return result(row, DispatchStatus.UNKNOWN, error, true);
    }

    private static RefundDispatchResult result(StlPayment row, DispatchStatus status, String error, boolean retry) {
        return new RefundDispatchResult(row.getRefundExecutionNo(), row.getPaymentNo(), status, error,
                retry, row.getSucceededAt());
    }
}
