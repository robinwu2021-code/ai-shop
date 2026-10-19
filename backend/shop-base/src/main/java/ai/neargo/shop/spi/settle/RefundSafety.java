package ai.neargo.shop.spi.settle;

import ai.neargo.shop.spi.pay.RefundQueryResult.Status;

/** Financial safety is independent of the legacy payment display status. */
public record RefundSafety(Status outcome, boolean terminalVerified, boolean originFrozen, String errorCode) {
    public static RefundSafety unknown() { return new RefundSafety(Status.UNKNOWN, false, false, "REFUND_TERMINAL_PROOF_REQUIRED"); }
    public boolean safeToRecover() { return outcome == Status.TERMINAL_FAILED && terminalVerified && !originFrozen; }
}
