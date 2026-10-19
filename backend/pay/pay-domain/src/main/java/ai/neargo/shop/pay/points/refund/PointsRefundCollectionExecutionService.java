package ai.neargo.shop.pay.points.refund;

import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;

/** Runs the committed collection command outside every database transaction. */
public interface PointsRefundCollectionExecutionService {
    FundsResult execute(String preparationNo);
}
