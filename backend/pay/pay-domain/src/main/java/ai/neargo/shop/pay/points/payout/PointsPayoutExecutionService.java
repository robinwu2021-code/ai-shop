package ai.neargo.shop.pay.points.payout;

import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutOutcome;

/** Transaction-free orchestration submits only the first dispatch and queries subsequent uncertain outcomes. */
public interface PointsPayoutExecutionService {
    /** Executes or recovers a committed ticket using its original immutable funds command. */
    PayoutOutcome execute(String ticketNo);
}
