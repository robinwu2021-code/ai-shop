package ai.neargo.shop.trade.order.points;

import ai.neargo.shop.spi.points.PointsPaymentSnapshotPort.VerifiedPayment;

/** Appends source facts in the same transaction as the trusted paid order transition. */
public interface OrderPointsPaidFactService {
    void recordPaid(String orderNo, VerifiedPayment payment);
}
