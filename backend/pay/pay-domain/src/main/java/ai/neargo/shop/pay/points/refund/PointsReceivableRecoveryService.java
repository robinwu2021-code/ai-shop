package ai.neargo.shop.pay.points.refund;

import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;

/** Store debt recovery locks unpaid R and replenishes B only from an actual incoming cash receipt. */
public interface PointsReceivableRecoveryService {
    FundsCommand prepare(String bizRef,String receivableNo,long amountMinor);
    Claim claim(String ticketNo);
    Outcome accept(FundsResult result);
    record Claim(FundsCommand command,boolean submit,Outcome completed) { }
    record Outcome(String ticketNo,FundsStatus status,long confirmedMinor,boolean replayed) { }
}
