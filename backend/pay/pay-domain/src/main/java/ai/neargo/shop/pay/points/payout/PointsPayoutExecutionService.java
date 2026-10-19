package ai.neargo.shop.pay.points.payout;

import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutOutcome;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;

/** Transaction-free orchestration submits only the first dispatch and queries subsequent uncertain outcomes. */
public interface PointsPayoutExecutionService {
    /** Executes or recovers a committed ticket using its original immutable funds command. */
    PayoutOutcome execute(String ticketNo);

    /** Returns gateway evidence for confirmation together with the bill in a separate short pay transaction. */
    DispatchResult perform(String ticketNo);

    /** A terminal replay requires no gateway call or new confirmation. */
    record DispatchResult(FundsResult result, PayoutOutcome completed) { }
}
