package ai.neargo.shop.pay.points.payout;

import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutCommand;
import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutTicket;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import java.util.List;

/** Original preparation receipt and money command remain immutable even when a later result arrives. */
public record PayoutSnapshot(PayoutCommand command,PayoutTicket ticket,FundsCommand fundsCommand,FundsResult result,
                             String statementNo,long statementRevision,List<Offset> offsets) {
    public PayoutSnapshot { offsets=offsets==null?List.of():List.copyOf(offsets); }
    public PayoutSnapshot(PayoutCommand command,PayoutTicket ticket,FundsCommand fundsCommand,FundsResult result) {
        this(command,ticket,fundsCommand,result,null,0,List.of());
    }
    public PayoutSnapshot(PayoutCommand command,PayoutTicket ticket,FundsCommand fundsCommand) {
        this(command,ticket,fundsCommand,null);
    }
    public record Offset(String receivableNo,long amountMinor) { }
}
