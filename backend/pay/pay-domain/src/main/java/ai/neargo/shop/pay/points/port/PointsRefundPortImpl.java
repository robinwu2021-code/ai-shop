package ai.neargo.shop.pay.points.port;

import ai.neargo.shop.pay.points.refund.PointsRefundAbortService;
import ai.neargo.shop.pay.points.refund.PointsRefundConfirmationService;
import ai.neargo.shop.pay.points.refund.PointsRefundPreparationService;
import ai.neargo.shop.spi.points.PointsRefundPort;
import org.springframework.stereotype.Component;

/** The order domain exchanges frozen DTOs; pay retains all points and financial mutations. */
@Component
public class PointsRefundPortImpl implements PointsRefundPort {
    private final PointsRefundPreparationService preparations;
    private final PointsRefundConfirmationService confirmations;
    private final PointsRefundAbortService aborts;

    public PointsRefundPortImpl(PointsRefundPreparationService preparations,
                                PointsRefundConfirmationService confirmations, PointsRefundAbortService aborts) {
        this.preparations = preparations;
        this.confirmations = confirmations;
        this.aborts = aborts;
    }

    @Override public RefundPreparation prepare(RefundCommand command) { return preparations.prepare(command); }
    @Override public RefundOutcome confirm(RefundConfirmation confirmation) {
        return confirmations.confirm(confirmation);
    }
    @Override public void abort(RefundAbort abort) { aborts.abort(abort); }
}
