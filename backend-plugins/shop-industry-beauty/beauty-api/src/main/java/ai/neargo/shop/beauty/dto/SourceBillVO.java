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
        List<String> blockingReasons) {
    public SourceBillVO {
        receipts = List.copyOf(receipts);
        tasks = List.copyOf(tasks);
        blockingReasons = List.copyOf(blockingReasons);
    }
}
