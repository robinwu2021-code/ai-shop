package ai.neargo.shop.pay.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.acquiring.service.RefundOutcomeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.spi.pay.RefundQueryResult;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundRecoveryStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.Objects;

/** Shared by both cash paths. Failed is not released without terminal and compensation evidence. */
@Component
@Transactional("payTxManager")
public class RefundCapacityLedger {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RefundCapacityLedger.class);
    private final PaymentMapper payments;
    private final ObjectMapper json = new ObjectMapper();
    public RefundCapacityLedger(PaymentMapper payments) { this.payments = payments; }

    private ObjectProvider<RefundOutcomeService> refundOutcomes;

    @Autowired
    void setRefundOutcomes(ObjectProvider<RefundOutcomeService> refundOutcomes) {
        this.refundOutcomes = refundOutcomes;
    }

    /** Caller has already locked the original receipt; all allocations serialize on that row. */
    public void check(StlPayment origin, long amount) {
        if ("FROZEN".equals(origin.getRefundCapacityState()))
            throw new IllegalStateException("REFUND_ORIGIN_FROZEN");
        long reserved = 0;
        var rows = DataScopeContext.executeWithoutScope(() -> payments.selectList(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getDirection, StlPayment.REFUND)
                .eq(StlPayment::getOrderNo, origin.getOrderNo())
                .and(q -> q.eq(StlPayment::getOriginPaymentNo, origin.getPaymentNo())
                        .or().isNull(StlPayment::getOriginPaymentNo)).last("FOR UPDATE")));
        for (var row : rows) {
            if (!"RELEASED".equals(row.getRefundCapacityState()))
                reserved = Math.addExact(reserved, row.getAmountMinor() == null ? 0 : row.getAmountMinor());
        }
        if (amount < 0 || Math.addExact(reserved, amount) > origin.getAmountMinor())
            throw BizException.of(ErrorCode.CONFLICT);
    }

    public ai.neargo.shop.spi.settle.RefundSafety safety(StlPayment row) {
        var origin = row == null ? null : DataScopeContext.executeWithoutScope(() -> payments.selectOne(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getDirection, StlPayment.PAY)
                .eq(StlPayment::getOrderNo, row.getOrderNo())
                .eq(row.getOriginPaymentNo() != null, StlPayment::getPaymentNo, row.getOriginPaymentNo())
                .eq(StlPayment::getStatus, StlPayment.SUCCESS).orderByAsc(StlPayment::getId).last("LIMIT 1")));
        return ai.neargo.shop.pay.policy.RefundOutcomePolicy.resolve(row,
                origin != null && "FROZEN".equals(origin.getRefundCapacityState()));
    }

    public StlPayment lockOriginal(StlPayment refund) {
        if (refund == null) throw new IllegalArgumentException("PAY_REFUND_UNKNOWN");
        return DataScopeContext.executeWithoutScope(() -> payments.selectOne(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getDirection, StlPayment.PAY)
                .eq(StlPayment::getOrderNo, refund.getOrderNo())
                .eq(refund.getOriginPaymentNo() != null, StlPayment::getPaymentNo, refund.getOriginPaymentNo())
                .eq(StlPayment::getStatus, StlPayment.SUCCESS).orderByAsc(StlPayment::getId)
                .last("LIMIT 1 FOR UPDATE")));
    }

    public void releaseAfterRecovery(RefundRecoveryStatus proof) {
        require(proof != null && "ABORTED".equals(proof.state())
                && (!proof.collectionRequired() || proof.compensationSucceeded()), "REFUND_RECOVERY_REQUIRED");
        var row = byNo(proof.cashRefundPaymentNo());
        var origin = lockOriginal(row);
        require(origin != null, "REFUND_ORIGINAL_REQUIRED");
        row = locked(row.getPaymentNo());
        require(!"FROZEN".equals(origin.getRefundCapacityState()), "REFUND_ORIGIN_FROZEN");
        require(Objects.equals(row.getAfterSaleNo(), proof.afterSaleNo())
                && Objects.equals(row.getSubOrderNo(), proof.subOrderNo())
                && row.getAmountMinor() == proof.netCashMinor(), "REFUND_RECOVERY_MISMATCH");
        require(StlPayment.FAILED.equals(row.getStatus())
                && "TERMINAL_FAILED".equals(row.getRefundQueryState())
                && hasProof(row), "REFUND_TERMINAL_PROOF_REQUIRED");
        if ("RELEASED".equals(row.getRefundCapacityState())) {
            require(json.writeValueAsString(proof).equals(row.getRefundRecoveryProof()), "REFUND_RECOVERY_CONFLICT");
            return;
        }
        update(row, p -> {
            p.setRefundCapacityState("RELEASED");
            p.setRefundRecoveryProof(json.writeValueAsString(proof));
        });
    }

    /** Called inside the same transaction as a verified success. False retains a late-success anomaly. */
    public boolean consume(StlPayment row, String provider, long amount) {
        if ("LATE_SUCCESS".equals(row.getRefundQueryState())) return false;
        if ("RELEASED".equals(row.getRefundCapacityState())
                || (StlPayment.FAILED.equals(row.getStatus()) && hasProof(row))) {
            var origin = lockOriginal(row);
            require(origin != null, "REFUND_ORIGINAL_REQUIRED");
            update(origin, p -> p.setRefundCapacityState("FROZEN"));
            log.error("退款终止证据与成功回执矛盾，冻结新增退款 origin={} refund={} provider={}",
                    origin.getPaymentNo(), row.getPaymentNo(), provider);
            update(row, p -> {
                p.setRefundQueryState("LATE_SUCCESS"); p.setErrCode("REFUND_LATE_SUCCESS_FROZEN");
                p.setRefundQueryAt(System.currentTimeMillis());
                p.setRefundTerminalProof(json.writeValueAsString(java.util.Map.of(
                        "terminalProof", row.getRefundTerminalProof(), "lateProvider", provider,
                        "lateAmountMinor", amount, "at", System.currentTimeMillis())));
            });
            return false;
        }
        update(row, p -> p.setRefundCapacityState("CONSUMED"));
        return true;
    }

    public void observe(String paymentNo, RefundQueryResult result) {
        if (result == null) return;
        var row = byNo(paymentNo);
        lockOriginal(row);
        row = locked(paymentNo);
        require(Objects.equals(row.getOutTradeNo(), result.outRefundNo()), "REFUND_QUERY_IDENTITY_MISMATCH");
        if ("LATE_SUCCESS".equals(row.getRefundQueryState())) return;
        if (StlPayment.FAILED.equals(row.getStatus()) && hasProof(row)
                && result.status() != RefundQueryResult.Status.SUCCEEDED) return;
        if (result.status() == RefundQueryResult.Status.SUCCEEDED) return; // validated by the success writer
        if ("MANUAL_REQUIRED".equals(row.getRefundQueryState())
                && result.status() != RefundQueryResult.Status.TERMINAL_FAILED) return;
        boolean terminal = result.status() == RefundQueryResult.Status.TERMINAL_FAILED;
        if (terminal) require(result.observedAt() > 0 && result.providerNo() != null
                && !result.providerNo().isBlank() && Objects.equals(row.getCurrency(), result.currency())
                && row.getAmountMinor() == result.amountMinor(), "REFUND_TERMINAL_RECEIPT_MISMATCH");
        if (StlPayment.SUCCESS.equals(row.getStatus()) || "RELEASED".equals(row.getRefundCapacityState())) return;
        update(row, p -> {
            p.setRefundQueryState(result.status().name());
            p.setRefundQueryRaw(result.rawStatus());
            p.setRefundQueryAt(result.observedAt());
            if (result.status() == RefundQueryResult.Status.MANUAL_REQUIRED) p.setErrCode("REFUND_MANUAL_REQUIRED");
            if (terminal) {
                p.setStatus(StlPayment.FAILED);
                p.setRefundTerminalProof(json.writeValueAsString(result));
                p.setErrCode("REFUND_TERMINAL_CLOSED");
            }
        });
        if (terminal && refundOutcomes != null && row.getRefundExecutionNo() != null) {
            String executionNo = row.getRefundExecutionNo();
            refundOutcomes.ifAvailable(service -> service.failed(executionNo, "REFUND_TERMINAL_CLOSED"));
        }
    }

    /** A gateway's definitive, non-retryable rejection, never a timeout or thrown exception. */
    public void rejected(StlPayment row, String reason) {
        var current = locked(row.getPaymentNo());
        if (current == null || hasProof(current)
                || StlPayment.SUCCESS.equals(current.getStatus())) return;
        update(current, p -> {
            p.setRefundQueryState("TERMINAL_FAILED"); p.setRefundQueryRaw("REJECTED");
            p.setRefundQueryAt(System.currentTimeMillis());
            p.setRefundTerminalProof(json.writeValueAsString(java.util.Map.of(
                    "paymentNo", row.getPaymentNo(), "outRefundNo", row.getOutTradeNo(),
                    "reason", reason == null ? "rejected" : reason, "at", System.currentTimeMillis())));
        });
    }
    private StlPayment byNo(String no) {
        return DataScopeContext.executeWithoutScope(() -> payments.selectOne(Wrappers.<StlPayment>lambdaQuery()
                .eq(StlPayment::getPaymentNo, no).eq(StlPayment::getDirection, StlPayment.REFUND)));
    }
    private StlPayment locked(String no) {
        return DataScopeContext.executeWithoutScope(() -> payments.selectOne(Wrappers.<StlPayment>lambdaQuery()
                .eq(StlPayment::getPaymentNo, no).eq(StlPayment::getDirection, StlPayment.REFUND).last("FOR UPDATE")));
    }
    private void update(StlPayment row, java.util.function.Consumer<StlPayment> change) {
        var patch = new StlPayment(); patch.setId(row.getId()); change.accept(patch);
        DataScopeContext.executeWithoutScope(() -> payments.updateById(patch));
    }
    private boolean hasProof(StlPayment row) { return row.getRefundTerminalProof()!=null && !row.getRefundTerminalProof().isBlank(); }
    private void require(boolean ok, String message) { if (!ok) throw new IllegalArgumentException(message); }
}
