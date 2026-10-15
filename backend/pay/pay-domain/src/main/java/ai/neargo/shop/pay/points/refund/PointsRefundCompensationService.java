package ai.neargo.shop.pay.points.refund;

import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundAbort;

/** Returns an actually collected offset before permitting restoration of a failed cash refund's freezes. */
public interface PointsRefundCompensationService {
    /** Binds a definite failed cash leg and the exact authorized revision before any money is sent. */
    FundsCommand prepare(RefundAbort abort, long revision);

    /** Commits dispatch once; every subsequent attempt queries the same original command. */
    Dispatch claim(String preparationNo);

    /** Persists evidence and outcome atomically; uncertainty or rejection retains all freezes. */
    FundsResult accept(String preparationNo, FundsResult result);

    record Dispatch(FundsCommand command, boolean submit, FundsResult completed) { }

    /** The refund head version serializes confirmation and compensation using this immutable intent and result. */
    record CompensationSnapshot(RefundAbort abort, FundsCommand command, FundsResult result) { }
}
