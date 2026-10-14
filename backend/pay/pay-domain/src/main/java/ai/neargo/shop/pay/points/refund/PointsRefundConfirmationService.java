package ai.neargo.shop.pay.points.refund;

import ai.neargo.shop.spi.points.PointsRefundPort.RefundConfirmation;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundOutcome;

/** Confirms frozen rights only against trusted, exactly bound cash and funds evidence. */
public interface PointsRefundConfirmationService {
    RefundOutcome confirm(RefundConfirmation confirmation);
}
