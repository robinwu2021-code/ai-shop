package ai.neargo.shop.trade.order.machine.aftersale;

/** Trusted service snapshot of an existing decision and settled authority facts. Never an HTTP command. */
public record RefundTerminalProof(boolean decisionApproved,long cashMinor,boolean cashConfirmed,
                                  boolean benefitsSettled,boolean offlineAcknowledged) {
    public RefundTerminalProof {
        if(cashMinor<0) throw new IllegalArgumentException("REFUND_TERMINAL_CASH_INVALID");
    }
}
