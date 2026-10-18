package ai.neargo.shop.pay.points.refund;

import ai.neargo.shop.spi.points.PointsRefundPort.RefundAbort;

/** Releases the original frozen buckets only after definitive evidence that cash was not refunded. */
public interface PointsRefundAbortService {
    void abort(RefundAbort abort);
}
