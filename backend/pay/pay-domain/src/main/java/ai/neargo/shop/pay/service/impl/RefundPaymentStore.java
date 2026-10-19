package ai.neargo.shop.pay.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.spi.settle.RefundRequest;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.spi.settle.RefundDispatchResult.DispatchStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A committed INIT row precedes the one-time send claim; PENDING is queried, never sent again. */
@Component
public class RefundPaymentStore {
    private final PaymentMapper payments;

    private final RefundCapacityLedger capacity;
    public RefundPaymentStore(PaymentMapper payments, RefundCapacityLedger capacity) {
        this.capacity = capacity;
        this.payments = payments;
    }

    public StlPayment byExecution(String no) {
        return DataScopeContext.executeWithoutScope(() -> payments.selectOne(Wrappers.<StlPayment>lambdaQuery()
                .eq(StlPayment::getDirection, StlPayment.REFUND).eq(StlPayment::getRefundExecutionNo, no)));
    }

    public StlPayment origin(String order, String trade, String channel) {
        return DataScopeContext.executeWithoutScope(() -> payments.selectOne(Wrappers.<StlPayment>lambdaQuery()
                .eq(StlPayment::getOrderNo, order).eq(StlPayment::getDirection, StlPayment.PAY)
                .eq(StlPayment::getStatus, StlPayment.SUCCESS).eq(StlPayment::getTradeNo, trade)
                .eq(StlPayment::getPayChannel, channel).last("LIMIT 1")));
    }

    public StlPayment original(String paymentNo) {
        return DataScopeContext.executeWithoutScope(() -> payments.selectOne(Wrappers.<StlPayment>lambdaQuery()
                .eq(StlPayment::getPaymentNo, paymentNo).eq(StlPayment::getDirection, StlPayment.PAY)
                .eq(StlPayment::getStatus, StlPayment.SUCCESS)));
    }

    @Transactional(value = "payTxManager", propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public StlPayment prepare(RefundRequest request) {
        StlPayment origin = DataScopeContext.executeWithoutScope(() -> payments.selectOne(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getPaymentNo, request.originPaymentNo())
                        .eq(StlPayment::getDirection, StlPayment.PAY).last("FOR UPDATE")));
        if (origin == null || !StlPayment.SUCCESS.equals(origin.getStatus())
                || !Objects.equals(origin.getCurrency(), request.currency())
                || origin.getAmountMinor() == null || request.amountMinor() < 0
                || request.amountMinor() > origin.getAmountMinor()) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        StlPayment old = byExecution(request.executionNo());
        if (old != null) {
            if (!RefundRequestIdentity.matches(old, origin, request)) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return old;
        }
        if (DataScopeContext.executeWithoutScope(() -> payments.selectCount(Wrappers.<StlPayment>lambdaQuery()
                .eq(StlPayment::getDirection, StlPayment.REFUND)
                .eq(StlPayment::getAfterSaleNo, request.afterSaleNo()))) > 0) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        capacity.check(origin, request.amountMinor());
        StlPayment row = row(origin, request);
        DataScopeContext.executeWithoutScope(() -> payments.insert(row));
        return row;
    }

    @Transactional(value = "payTxManager", propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public RefundDispatchResult confirm(String no, String channel, long amount, String currency, String provider) {
        return DataScopeContext.executeWithoutScope(() -> confirmCurrent(no, channel, amount, currency, provider));
    }

    private RefundDispatchResult confirmCurrent(String no, String channel, long amount,
                                                String currency, String provider) {
        StlPayment candidate = DataScopeContext.executeWithoutScope(() -> payments.selectOne(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getOutTradeNo, no)
                        .eq(StlPayment::getDirection, StlPayment.REFUND)));
        if (candidate != null) capacity.lockOriginal(candidate);
        StlPayment row = payments.selectOne(Wrappers.<StlPayment>lambdaQuery()
                .eq(StlPayment::getOutTradeNo, no).eq(StlPayment::getDirection, StlPayment.REFUND).last("FOR UPDATE"));
        if (row == null) {
            return new RefundDispatchResult(null, null, DispatchStatus.FAILED, "REFUND_NOT_FOUND", false, null);
        }
        if (provider == null || provider.isBlank() || !Objects.equals(row.getPayChannel(), channel)
                || !Objects.equals(row.getCurrency(), currency) || !Objects.equals(row.getAmountMinor(), amount)
                || (row.getTradeNo() != null && !Objects.equals(row.getTradeNo(), provider))) {
            return new RefundDispatchResult(row.getRefundExecutionNo(), row.getPaymentNo(), DispatchStatus.FAILED,
                    "REFUND_RECEIPT_MISMATCH", false, null);
        }
        if (!capacity.consume(row, provider, amount))
            return new RefundDispatchResult(row.getRefundExecutionNo(), row.getPaymentNo(),
                    DispatchStatus.UNKNOWN, "REFUND_LATE_SUCCESS_FROZEN", false, null);
        if (!StlPayment.SUCCESS.equals(row.getStatus())) {
            long now = System.currentTimeMillis();
            int changed = payments.update(null, Wrappers.<StlPayment>lambdaUpdate()
                    .eq(StlPayment::getId, row.getId()).eq(StlPayment::getVersion, row.getVersion())
                    .set(StlPayment::getStatus, StlPayment.SUCCESS).set(StlPayment::getTradeNo, provider)
                    .set(StlPayment::getSucceededAt, now).set(StlPayment::getErrCode, null)
                    .setSql("version = version + 1"));
            if (changed != 1) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            row.setSucceededAt(now);
        }
        return new RefundDispatchResult(row.getRefundExecutionNo(), row.getPaymentNo(), DispatchStatus.SUCCEEDED,
                null, false, row.getSucceededAt());
    }

    private static StlPayment row(StlPayment origin, RefundRequest request) {
        StlPayment row = new StlPayment();
        row.setPaymentNo(BizKey.next(BizKey.PAYMENT));
        row.setDirection(StlPayment.REFUND);
        row.setStatus(StlPayment.INIT);
        row.setRefundExecutionNo(request.executionNo());
        row.setOriginPaymentNo(origin.getPaymentNo());
        row.setRefundCapacityState("RESERVED");
        row.setOutTradeNo(request.executionNo());
        row.setOrderNo(origin.getOrderNo());
        row.setSubOrderNo(request.subOrderNo());
        row.setAfterSaleNo(request.afterSaleNo());
        row.setUserNo(origin.getUserNo());
        row.setPayChannel(origin.getPayChannel());
        row.setPayScene(origin.getPayScene());
        row.setPayMethod(origin.getPayMethod());
        row.setCurrency(origin.getCurrency());
        row.setAmountMinor(request.amountMinor());
        row.setErrMsg(request.reason());
        return row;
    }

    @Transactional(value = "payTxManager", propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public RefundDispatchResult completeZero(String no) {
        StlPayment row = DataScopeContext.executeWithoutScope(() -> payments.selectOne(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getRefundExecutionNo, no)
                        .eq(StlPayment::getDirection, StlPayment.REFUND).last("FOR UPDATE")));
        if (row == null || !Long.valueOf(0).equals(row.getAmountMinor())
                || original(row.getOriginPaymentNo()) == null) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (!StlPayment.SUCCESS.equals(row.getStatus())) {
            if (!StlPayment.PENDING.equals(row.getStatus())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            row.setSucceededAt(System.currentTimeMillis());
            int changed = DataScopeContext.executeWithoutScope(() -> payments.update(null,
                    Wrappers.<StlPayment>lambdaUpdate().eq(StlPayment::getId, row.getId())
                            .eq(StlPayment::getStatus, StlPayment.PENDING)
                            .set(StlPayment::getStatus, StlPayment.SUCCESS).set(StlPayment::getErrCode, null)
                            .set(StlPayment::getSucceededAt, row.getSucceededAt()).setSql("version = version + 1")));
            if (changed != 1) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
        }
        return new RefundDispatchResult(no, row.getPaymentNo(), DispatchStatus.SUCCEEDED,
                null, false, row.getSucceededAt());
    }

    @Transactional(value = "payTxManager", propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String no) {
        var origin = capacity.lockOriginal(byExecution(no));
        if (origin != null && "FROZEN".equals(origin.getRefundCapacityState())) return false;
        return DataScopeContext.executeWithoutScope(() -> payments.update(null,
                Wrappers.<StlPayment>lambdaUpdate().eq(StlPayment::getRefundExecutionNo, no)
                        .eq(StlPayment::getDirection, StlPayment.REFUND).eq(StlPayment::getStatus, StlPayment.INIT)
                        .and(q -> q.isNull(StlPayment::getRefundQueryState).or()
                                .notIn(StlPayment::getRefundQueryState,"PROCESSING","MANUAL_REQUIRED","LATE_SUCCESS","TERMINAL_FAILED"))
                        .set(StlPayment::getStatus, StlPayment.PENDING).set(StlPayment::getErrCode, "DISPATCHING")
                        .setSql("version = version + 1"))) == 1;
    }

    @Transactional(value = "payTxManager", propagation = Propagation.REQUIRES_NEW)
    public void reject(String no, String error) {
        var row = byExecution(no);
        if (row == null) throw new IllegalArgumentException("PAY_REFUND_UNKNOWN");
        capacity.rejected(row, error);
        result(no, StlPayment.FAILED, error, null);
    }

    @Transactional(value = "payTxManager", propagation = Propagation.REQUIRES_NEW)
    public void result(String no, String status, String error, String provider) {
        DataScopeContext.executeWithoutScope(() -> payments.update(null, Wrappers.<StlPayment>lambdaUpdate()
                .eq(StlPayment::getRefundExecutionNo, no).eq(StlPayment::getDirection, StlPayment.REFUND)
                .in(StlPayment::getStatus, StlPayment.INIT, StlPayment.PENDING).set(StlPayment::getStatus, status)
                .set(StlPayment::getErrCode, error).set(provider != null, StlPayment::getTradeNo, provider)
                .set(StlPayment.SUCCESS.equals(status), StlPayment::getSucceededAt, System.currentTimeMillis())
                .setSql("version = version + 1")));
    }
}
