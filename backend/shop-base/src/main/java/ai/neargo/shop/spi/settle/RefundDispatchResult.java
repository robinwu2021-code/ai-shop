package ai.neargo.shop.spi.settle;

/** Acceptance and uncertainty are deliberately separate from confirmed cash success. */
public record RefundDispatchResult(String executionNo, String paymentNo, DispatchStatus status,
                                   String errorCode, boolean retryable, Long succeededAt) {
    public enum DispatchStatus { ACCEPTED, SUCCEEDED, FAILED, UNKNOWN }
}
