package ai.neargo.shop.pay.points.payout;

import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutTicket;

/** A periodic ticket freezes both statement revision and specific store receivables before execution. */
public interface StatementPayoutService {
    PayoutTicket prepare(String bizRef, String statementNo, long revision, String actor);
}
