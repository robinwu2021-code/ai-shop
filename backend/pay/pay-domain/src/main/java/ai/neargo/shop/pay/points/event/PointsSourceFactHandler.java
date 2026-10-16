package ai.neargo.shop.pay.points.event;

import ai.neargo.shop.spi.points.PointsEarnPort.GrantCommand;
import ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact;
import ai.neargo.shop.spi.points.PointsFactPort.RefundConfirmationFact;

/** Applies a trusted source fact in one pay transaction. */
public interface PointsSourceFactHandler {
    void apply(LifecycleFact fact);

    void earn(GrantCommand command);

    void confirm(RefundConfirmationFact fact);
}
