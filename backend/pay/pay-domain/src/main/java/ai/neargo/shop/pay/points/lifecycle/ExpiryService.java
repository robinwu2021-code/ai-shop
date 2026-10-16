package ai.neargo.shop.pay.points.lifecycle;

import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;

/** An expiry reserves only AVAILABLE rights; verified income movement later consumes that exact hold. */
public interface ExpiryService {
    FundsCommand prepare(Request request);
    Dispatch claim(String ticketNo);
    FundsResult accept(String ticketNo, FundsResult result);
    record Request(String typeCode,String userNo,long expiryRevision,long asOf,FundsContext incomeAccount) { }
    record Dispatch(FundsCommand command,boolean submit,FundsResult completed) { }
    record Snapshot(String typeCode,String userNo,long expiryRevision,long expireAt,long points,
                    FundsCommand fundsCommand,FundsResult result) { }
}
