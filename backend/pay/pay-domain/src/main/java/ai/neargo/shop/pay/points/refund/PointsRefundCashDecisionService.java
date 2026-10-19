package ai.neargo.shop.pay.points.refund;

import ai.neargo.shop.spi.points.PointsRefundPort.PaymentSnapshot;

/** Freezes a resolved positive-cash decision before a cash intent may be created by its caller. */
public interface PointsRefundCashDecisionService {
    /** Commits authorization for the original store/account and resolved revision, without executing cash. */
    Decision authorize(String preparationNo, String inputHash, long revision);

    /** Immutable permission to use exactly this preparation revision and cash amount. */
    record Decision(String preparationNo, String inputHash, long revision, String afterSaleNo,
                    String subOrderNo, String userNo, String currency, String channel,
                    long netCashMinor, long cashOffsetMinor, String collectionTicket, PaymentSnapshot payment) {
    }
}
