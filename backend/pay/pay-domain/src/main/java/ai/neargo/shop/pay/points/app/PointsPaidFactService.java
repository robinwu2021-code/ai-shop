package ai.neargo.shop.pay.points.app;

import ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact;

/** Internal paid-fact consumer; authoritative source wiring is owned by the durable lifecycle pipeline. */
public interface PointsPaidFactService {
    /** Converts committed held rights according to the original store agreement and price snapshot. */
    void onPaid(LifecycleFact fact);
}
