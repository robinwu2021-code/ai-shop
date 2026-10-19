package ai.neargo.shop.trade.order.points;

/**
 * Records the original completion and later refund-responsibility closure as distinct source facts.
 */
public interface OrderPointsClosureFlowService {
    boolean finalizeSubOrder(String subOrderNo);

    boolean closeRefundResponsibility(String subOrderNo);
}
