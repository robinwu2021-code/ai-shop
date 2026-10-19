package ai.neargo.shop.pay.points.refund;

import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;

/** Sends or queries a committed collection reversal outside the pay transaction. */
public interface PointsRefundCompensationExecutionService {
    FundsResult execute(String preparationNo);
}
