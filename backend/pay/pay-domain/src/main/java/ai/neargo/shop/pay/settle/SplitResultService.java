package ai.neargo.shop.pay.settle;

import ai.neargo.shop.pay.SplitGateway;
import ai.neargo.shop.pay.points.payout.PointsPayoutExecutionService.DispatchResult;

/** Only verified local transitions are persisted; accepted split commands are not arrival confirmations. */
public interface SplitResultService {
    boolean acceptPoints(String requestNo,DispatchResult result);
    void acceptCash(String requestNo,SplitGateway.Result result);
}
