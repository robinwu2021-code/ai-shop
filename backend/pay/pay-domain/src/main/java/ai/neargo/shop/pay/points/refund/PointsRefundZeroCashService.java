package ai.neargo.shop.pay.points.refund;

import ai.neargo.shop.spi.points.PointsRefundPort.ZeroCashCompletionFact;

/** Trusted fact-handler entry only; never expose a manual success flag through an operations API. */
public interface PointsRefundZeroCashService {
    /** Persists a verified source cash-leg fact and authorizes its exact zero-cash preparation atomically. */
    void accept(ZeroCashCompletionFact fact);
}
