package ai.neargo.shop.spi.trade;

/**
 * Closed machine observations/effects carry no delegated HUMAN identity. Each HTTP route fixes one
 * purpose.
 */
public final class ExecutionRuntimeProtocol {
    private ExecutionRuntimeProtocol() {}

    public enum Purpose {
        RESOURCE_CANCEL,
        RESOURCE_ORIGINAL,
        RESOURCE_CURRENT,
        BOOKING_EXECUTION_ORIGINAL,
        LIFECYCLE_ORIGINAL,
        LIFECYCLE_CANCELLATION_ORIGINAL,
        START_ACK,
        START_ORIGINAL,
        COMPLETE,
        COMPLETION_ORIGINAL,
        START_AUTHORITY_ORIGINAL,
        CORE_EVIDENCE
    }

    public record MachineCall<T>(Purpose purpose, ExecutionReference reference, T request) {}

    public record MachineReply<T>(
            Purpose purpose, String referenceHash, String requestDigest, T result) {}
}
