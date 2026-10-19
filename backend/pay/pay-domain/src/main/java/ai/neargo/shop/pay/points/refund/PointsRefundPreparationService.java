package ai.neargo.shop.pay.points.refund;

import ai.neargo.shop.spi.points.PointsRefundPort.RefundCommand;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundPreparation;

/** Creates an immutable refund decision and reserves reclaimable rights before any external money operation. */
public interface PointsRefundPreparationService {
    RefundPreparation prepare(RefundCommand command);
}
