package ai.neargo.shop.pay.points.payout;

import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutCommand;
import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutTicket;
import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutOutcome;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand;

/** Preparation joins the caller's pay transaction; the returned funds command is executed after commit. */
public interface PointsPayoutService {
    /** Creates an immutable intent; no cash leaves D until an authoritative result is confirmed. */
    PayoutTicket beginPayout(PayoutCommand command);
    /** Returns the original money request after the preparation transaction has committed. */
    FundsCommand commandFor(String ticketNo);
    /** Commits dispatch ownership before the caller may send money; subsequent callers query the same request. */
    DispatchClaim claimDispatch(String ticketNo);
    /** Confirms verified money evidence and source positions atomically. */
    PayoutOutcome acceptResult(FundsResult result);
    record DispatchClaim(FundsCommand command,boolean submit,PayoutOutcome completed) { }
}
