package ai.neargo.shop.pay.points.model;

import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext;

/** Each maker/checker request has one immutable funding action and one declared amount. */
public record PoolRequest(String requestNo, String typeCode, Action action, long amountMinor,
                          String submittedBy, String evidenceNo, FundsContext fundsContext) {
    /** Incoming receipts and internal U→B moves have no outgoing bank target. */
    public PoolRequest(String requestNo,String typeCode,Action action,long amountMinor,
                       String submittedBy,String evidenceNo) {
        this(requestNo,typeCode,action,amountMinor,submittedBy,evidenceNo,null);
    }
    public enum Action { TOPUP_U, TOPUP_B, TRANSFER_U_TO_B, WITHDRAW_U, WITHDRAW_B }
}
