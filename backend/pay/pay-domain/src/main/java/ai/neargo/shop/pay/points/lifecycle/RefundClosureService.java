package ai.neargo.shop.pay.points.lifecycle;

import ai.neargo.shop.spi.points.PointsFactPort.RefundClosureFact;

/** Only an authoritative responsibility-closure fact can release reserved receive points and B. */
public interface RefundClosureService {
    Outcome close(RefundClosureFact fact);

    record Outcome(String subOrderNo, long releasedPoints, long releasedMinor, boolean replayed) {}
}
