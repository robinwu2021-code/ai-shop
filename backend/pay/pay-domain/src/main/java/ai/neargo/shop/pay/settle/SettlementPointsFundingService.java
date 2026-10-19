package ai.neargo.shop.pay.settle;

import ai.neargo.shop.pay.points.payout.PointsPayoutExecutionService.DispatchResult;

/** Aggregated goods funding is separate from the eventual payment to the merchant. */
public interface SettlementPointsFundingService {
    Plan prepare(String settleNo);
    boolean accept(String settleNo,DispatchResult result);
    void assertReady(String settleNo);
    void assertRefundClear(String settleNo);
    record Plan(String ticketNo,boolean ready) { }
}
