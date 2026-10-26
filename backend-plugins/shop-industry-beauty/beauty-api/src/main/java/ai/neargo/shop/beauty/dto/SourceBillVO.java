package ai.neargo.shop.beauty.dto;

import java.util.List;

/** Safe source workflow projection; observed allocation never grants execution authority. */
public record SourceBillVO(
        String checkoutOperationNo,
        String orderNo,
        String sourceNo,
        String currency,
        Long dueMinor,
        Long receivedMinor,
        Long refundedMinor,
        Long netReceivedMinor,
        Long outstandingMinor,
        String amountScope,
        List<ReceiptSummaryVO> receipts,
        List<SubmissionTaskVO> tasks,
        SourceObservationVO observation,
        boolean complete,
        List<String> blockingReasons,
        String admissionKind,
        String financialVerification,
        ManualReceiptSummaryVO manualReceipt,
        Long manualDeclaredMinor) {
    /** Retains the previous public constructor and funded projection defaults. */
    public SourceBillVO(
            String checkoutOperationNo,
            String orderNo,
            String sourceNo,
            String currency,
            Long dueMinor,
            Long receivedMinor,
            Long refundedMinor,
            Long netReceivedMinor,
            Long outstandingMinor,
            String amountScope,
            List<ReceiptSummaryVO> receipts,
            List<SubmissionTaskVO> tasks,
            SourceObservationVO observation,
            boolean complete,
            List<String> blockingReasons) {
        this(
                checkoutOperationNo,
                orderNo,
                sourceNo,
                currency,
                dueMinor,
                receivedMinor,
                refundedMinor,
                netReceivedMinor,
                outstandingMinor,
                amountScope,
                receipts,
                tasks,
                observation,
                complete,
                blockingReasons,
                "LEGACY_FUNDED",
                "VERIFIED_PAYMENT",
                null,
                null);
    }

    public SourceBillVO {
        receipts = List.copyOf(receipts);
        tasks = List.copyOf(tasks);
        blockingReasons = List.copyOf(blockingReasons);
    }
}
