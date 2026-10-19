package ai.neargo.shop.pay.points.refund;

/** Collects a committed store recovery outside database transactions; unknown results query the original intent. */
public interface PointsReceivableRecoveryExecutionService {
    PointsReceivableRecoveryService.Outcome execute(String ticketNo);
}
