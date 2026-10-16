package ai.neargo.shop.pay.points.job;

import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;

/**
 * Durable work points to an existing immutable financial intent; it never invents a bank command.
 */
public interface PointsRecoveryService {
    enum Operation {
        PAYOUT,
        TRANSFER,
        REFUND_COLLECTION,
        REFUND_COMPENSATION
    }

    record Work(String typeCode, Operation operation, String positionNo) {}

    void schedule(Work work);

    int discover(String typeCode, int limit, long now);

    FundsStatus execute(Work work);
}
