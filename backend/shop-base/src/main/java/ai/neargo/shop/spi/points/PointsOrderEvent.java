package ai.neargo.shop.spi.points;

import java.time.Instant;
import java.util.Objects;

/**
 * Frozen source-side envelope. Construct only from persisted order/payment facts, never a client
 * body. The existing pay facts remain the accounting protocol; this envelope binds their source
 * ownership. Currency/type ownership and payment evidence must additionally be verified by the host
 * adapter.
 */
public record PointsOrderEvent(
        String orderNo,
        String subOrderNo,
        String merchantNo,
        String storeNo,
        String userNo,
        String typeCode,
        String currency,
        long sourceVersion,
        Instant occurredAt,
        SourceFactsPort.Fact fact) {

    public PointsOrderEvent {
        require(
                nonblank(orderNo)
                        && nonblank(subOrderNo)
                        && nonblank(merchantNo)
                        && nonblank(storeNo)
                        && nonblank(userNo)
                        && nonblank(typeCode)
                        && currency != null
                        && currency.matches("[A-Z]{3}")
                        && sourceVersion >= 0
                        && occurredAt != null
                        && fact != null,
                "POINTS_ORDER_EVENT_INVALID");
        int count =
                (fact.lifecycle() == null ? 0 : 1)
                        + (fact.earning() == null ? 0 : 1)
                        + (fact.closure() == null ? 0 : 1)
                        + (fact.zeroCash() == null ? 0 : 1)
                        + (fact.refund() == null ? 0 : 1);
        require(fact.sequence() >= 0 && count == 1, "POINTS_ORDER_EVENT_PAYLOAD_INVALID");
        if (fact.lifecycle() != null) {
            var f = fact.lifecycle();
            identity(
                    subOrderNo,
                    userNo,
                    typeCode,
                    sourceVersion,
                    occurredAt,
                    f.subOrderNo(),
                    f.userNo(),
                    f.typeCode(),
                    f.sourceVersion(),
                    f.occurredAt());
            require(
                    f.kind() == PointsFactPort.FactKind.PAID
                            || f.kind() == PointsFactPort.FactKind.CANCELLED
                            || f.kind() == PointsFactPort.FactKind.FINALIZED,
                    "POINTS_TYPED_PROOF_REQUIRED");
            require(
                    nonblank(f.eventNo()) && nonblank(f.payloadHash()),
                    "POINTS_ORDER_EVENT_INVALID");
        } else if (fact.earning() != null) {
            var f = fact.earning();
            identity(
                    subOrderNo,
                    userNo,
                    typeCode,
                    sourceVersion,
                    occurredAt,
                    f.subOrderNo(),
                    f.userNo(),
                    f.typeCode(),
                    f.sourceVersion(),
                    occurredAt);
            require(
                    nonblank(f.eventNo())
                            && storeNo.equals(f.storeNo())
                            && merchantNo.equals(f.merchantNo()),
                    "POINTS_ORDER_STORE_MISMATCH");
            var payment = f.payment();
            require(
                    payment != null
                            && storeNo.equals(payment.storeNo())
                            && merchantNo.equals(payment.merchantNo())
                            && payment.orderTotalMinor() >= 0
                            && nonblank(payment.fundsPath())
                            && nonblank(payment.channel())
                            && nonblank(payment.accountVersion())
                            && nonblank(payment.outTradeNo()),
                    "POINTS_ORDER_PAYMENT_REQUIRED");
        } else if (fact.closure() != null) {
            var f = fact.closure();
            identity(
                    subOrderNo,
                    userNo,
                    typeCode,
                    sourceVersion,
                    occurredAt,
                    f.subOrderNo(),
                    f.userNo(),
                    f.typeCode(),
                    f.sourceVersion(),
                    f.occurredAt());
            require(
                    nonblank(f.eventNo())
                            && f.windowClosedAt() != null
                            && f.noOpenAfterSale()
                            && f.fundsFinal(),
                    "POINTS_CLOSURE_PROOF_INVALID");
        } else if (fact.zeroCash() != null) {
            var f = fact.zeroCash();
            identity(
                    subOrderNo,
                    userNo,
                    typeCode,
                    sourceVersion,
                    occurredAt,
                    f.subOrderNo(),
                    f.userNo(),
                    f.typeCode(),
                    f.sourceVersion(),
                    f.occurredAt());
            require(
                    nonblank(f.eventNo())
                            && storeNo.equals(f.storeNo())
                            && currency.equals(f.currency())
                            && f.reason() != null
                            && f.netCashMinor() == 0,
                    "POINTS_ZERO_CASH_FACT_INVALID");
        } else {
            var f = fact.refund();
            identity(
                    subOrderNo,
                    userNo,
                    typeCode,
                    sourceVersion,
                    occurredAt,
                    f.subOrderNo(),
                    f.userNo(),
                    f.typeCode(),
                    f.sourceVersion(),
                    f.occurredAt());
            require(
                    nonblank(f.eventNo()) && f.confirmation() != null,
                    "POINTS_REFUND_FACT_INVALID");
        }
    }

    public String eventNo() {
        if (fact.lifecycle() != null) return fact.lifecycle().eventNo();
        if (fact.earning() != null) return fact.earning().eventNo();
        if (fact.closure() != null) return fact.closure().eventNo();
        if (fact.zeroCash() != null) return fact.zeroCash().eventNo();
        return fact.refund().eventNo();
    }

    private static void identity(
            String sub,
            String user,
            String type,
            long version,
            Instant at,
            String payloadSub,
            String payloadUser,
            String payloadType,
            long payloadVersion,
            Instant payloadAt) {
        require(
                sub.equals(payloadSub)
                        && user.equals(payloadUser)
                        && type.equals(payloadType)
                        && version == payloadVersion
                        && Objects.equals(at, payloadAt),
                "POINTS_ORDER_EVENT_IDENTITY_MISMATCH");
    }

    private static boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean valid, String error) {
        if (!valid) throw new IllegalArgumentException(error);
    }
}
