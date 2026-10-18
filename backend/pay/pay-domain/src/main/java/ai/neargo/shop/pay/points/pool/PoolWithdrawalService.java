package ai.neargo.shop.pay.points.pool;

import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.pay.points.posting.PostingResult;

/** Approval reserves an immutable cash intent; actual money remains in U/B until authoritative confirmation. */
public interface PoolWithdrawalService {
    PostingResult prepareApproved(PoolRequest request);
    Claim claimDispatch(String ticketNo);
    Outcome acceptResult(FundsResult result);
    record Claim(FundsCommand command,boolean submit,Outcome completed) { }
    record Outcome(String ticketNo,FundsStatus status,long confirmedMinor,boolean replayed) { }
}
