package ai.neargo.shop.pay.policy;

import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.spi.pay.RefundQueryResult.Status;
import ai.neargo.shop.spi.settle.RefundSafety;

/** Every consumer interprets contradictory and legacy receipts the same way. */
public final class RefundOutcomePolicy {
    private RefundOutcomePolicy() { }
    public static RefundSafety resolve(StlPayment row, boolean frozen) {
        if (row == null) return RefundSafety.unknown();
        String state = row.getRefundQueryState();
        if ("LATE_SUCCESS".equals(state))
            return new RefundSafety(Status.MANUAL_REQUIRED, false, frozen, "REFUND_LATE_SUCCESS_FROZEN");
        if (StlPayment.SUCCESS.equals(row.getStatus()))
            return new RefundSafety(Status.SUCCEEDED, false, frozen, null);
        boolean proof = row.getRefundTerminalProof() != null && !row.getRefundTerminalProof().isBlank();
        if (StlPayment.FAILED.equals(row.getStatus()) && "TERMINAL_FAILED".equals(state) && proof)
            return new RefundSafety(Status.TERMINAL_FAILED, true, frozen, row.getErrCode());
        if ("MANUAL_REQUIRED".equals(state))
            return new RefundSafety(Status.MANUAL_REQUIRED, false, frozen, "REFUND_MANUAL_REQUIRED");
        if (row.getPayChannel()==null || row.getPayChannel().isBlank()
                || row.getOutTradeNo()==null || row.getOutTradeNo().isBlank())
            return new RefundSafety(Status.MANUAL_REQUIRED,false,frozen,"REFUND_QUERY_COORDINATES_REQUIRED");
        if ("PROCESSING".equals(state)) return new RefundSafety(Status.PROCESSING, false, frozen, null);
        return new RefundSafety(Status.UNKNOWN, false, frozen, row.getErrCode());
    }
}
