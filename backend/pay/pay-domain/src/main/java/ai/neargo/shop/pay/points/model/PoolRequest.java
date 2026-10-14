package ai.neargo.shop.pay.points.model;

/** Each maker/checker request has one immutable funding action and one declared amount. */
public record PoolRequest(String requestNo, String typeCode, Action action, long amountMinor,
                          String submittedBy, String evidenceNo) {
    public enum Action { TOPUP_U, TOPUP_B, TRANSFER_U_TO_B, WITHDRAW_U, WITHDRAW_B }
}
