package ai.neargo.shop.pay.points.payout;

import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutCommand;
import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutTicket;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;

/** Original preparation receipt and money command remain immutable even when a later result arrives. */
public record PayoutSnapshot(PayoutCommand command,PayoutTicket ticket,FundsCommand fundsCommand,FundsResult result) {
    public PayoutSnapshot(PayoutCommand command,PayoutTicket ticket,FundsCommand fundsCommand) {
        this(command,ticket,fundsCommand,null);
    }
}
