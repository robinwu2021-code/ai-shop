package ai.neargo.shop.pay.points.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.points.PointsPaymentSnapshotPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** A successful PAY row and its original request are the sole source of paid points facts. */
@Component
public class PointsPaymentSnapshotPortImpl implements PointsPaymentSnapshotPort {
    private final SettleMappers.PaymentMapper payments;
    private final PaymentLedgerService ledger;

    public PointsPaymentSnapshotPortImpl(
            SettleMappers.PaymentMapper payments, PaymentLedgerService ledger) {
        this.payments = payments;
        this.ledger = ledger;
    }

    @Override
    @Transactional(value = "payTxManager", readOnly = true)
    public Optional<VerifiedPayment> verified(String orderNo) {
        if (orderNo == null || orderNo.isBlank())
            throw new IllegalArgumentException("POINTS_PAYMENT_ORDER_REQUIRED");
        var rows =
                DataScopeContext.executeWithoutScope(
                        () ->
                                payments.selectList(
                                        Wrappers.<StlPayment>lambdaQuery()
                                                .eq(StlPayment::getDirection, StlPayment.PAY)
                                                .eq(StlPayment::getOrderNo, orderNo)
                                                .eq(StlPayment::getStatus, StlPayment.SUCCESS)
                                                .orderByAsc(StlPayment::getId)
                                                .last("LIMIT 2")));
        if (rows.isEmpty()) return Optional.empty();
        if (rows.size() != 1) throw new IllegalArgumentException("POINTS_PAYMENT_AMBIGUOUS");
        var paid = rows.getFirst();
        ai.neargo.shop.spi.settle.SettlePort.PaymentOpen request;
        try {
            request =
                    ledger.paymentRequest(paid.getOutTradeNo())
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "POINTS_PAYMENT_REQUEST_MISSING"));
        } catch (IllegalArgumentException inconsistent) {
            if ("PAY_REQUEST_SNAPSHOT_CONFLICT".equals(inconsistent.getMessage())) {
                throw new IllegalArgumentException("POINTS_PAYMENT_AMOUNT_MISMATCH", inconsistent);
            }
            throw inconsistent;
        }
        if (!Objects.equals(paid.getOrderNo(), request.orderNo())
                || !Objects.equals(paid.getUserNo(), request.userNo())
                || !Objects.equals(paid.getCurrency(), request.currency())
                || !Objects.equals(paid.getPayChannel(), request.payChannel())
                || paid.getAmountMinor() == null
                || paid.getAmountMinor() != request.amountMinor()) {
            throw new IllegalArgumentException("POINTS_PAYMENT_AMOUNT_MISMATCH");
        }
        if (paid.getSucceededAt() == null
                || paid.getSucceededAt() <= 0
                || paid.getTradeNo() == null
                || paid.getTradeNo().isBlank()
                || request.stores().isEmpty()) {
            throw new IllegalArgumentException("POINTS_PAYMENT_PROOF_INCOMPLETE");
        }
        return Optional.of(
                new VerifiedPayment(
                        orderNo,
                        paid.getUserNo(),
                        paid.getCurrency(),
                        paid.getAmountMinor(),
                        paid.getPayChannel(),
                        paid.getOutTradeNo(),
                        paid.getTradeNo(),
                        Instant.ofEpochMilli(paid.getSucceededAt()),
                        request.stores()));
    }
}
