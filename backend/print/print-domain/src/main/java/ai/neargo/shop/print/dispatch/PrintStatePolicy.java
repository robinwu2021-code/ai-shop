package ai.neargo.shop.print.dispatch;

import static ai.neargo.shop.print.dispatch.PrintStateModels.*;

import ai.neargo.shop.print.api.*;

import org.springframework.stereotype.Component;

/** No clock, persistence or IO: shared by production and isolated fixtures. */
@Component
public final class PrintStatePolicy {
    public PrintTransition decide(PrintStateSnapshot before, PrintObservation observation) {
        if (SetHolder.TERMINAL.contains(before.status()))
            return new PrintTransition(before.status(), false, false, "TERMINAL_UNCHANGED");
        if (before.partialOutput() || observation.partialOutput())
            return new PrintTransition(PrintJobStatus.UNKNOWN, false, false, "PARTIAL_OUTPUT");
        return switch (observation.status()) {
            case CONFIRMED ->
                    observation.evidence() == PrintEvidenceLevel.PROVIDER_CONFIRMED
                                    || observation.evidence() == PrintEvidenceLevel.DEVICE_CONFIRMED
                                    || observation.evidence() == PrintEvidenceLevel.MANUAL_CONFIRMED
                            ? new PrintTransition(
                                    PrintJobStatus.COMPLETED, true, false, "CONFIRMED")
                            : new PrintTransition(
                                    PrintJobStatus.UNKNOWN,
                                    false,
                                    false,
                                    "INSUFFICIENT_COMPLETION_EVIDENCE");
            case CANCELLED ->
                    observation.queueTerminated()
                            ? new PrintTransition(
                                    PrintJobStatus.CANCELLED, true, false, "CANCEL_CONFIRMED")
                            : new PrintTransition(
                                    PrintJobStatus.UNKNOWN, false, false, "CANCEL_UNCERTAIN");
            case NOT_ACCEPTED ->
                    observation.queueTerminated()
                            ? new PrintTransition(
                                    before.retryCount() < before.maxRetries()
                                            ? PrintJobStatus.RETRY_WAIT
                                            : PrintJobStatus.FAILED,
                                    true,
                                    before.retryCount() < before.maxRetries(),
                                    "REJECTED_BEFORE_ACCEPT")
                            : new PrintTransition(
                                    PrintJobStatus.UNKNOWN, false, false, "REJECTION_UNCERTAIN");
            case FAILED ->
                    observation.queueTerminated()
                            ? new PrintTransition(
                                    PrintJobStatus.FAILED, true, false, "TERMINAL_FAILURE")
                            : new PrintTransition(
                                    PrintJobStatus.UNKNOWN, false, false, "FAILURE_UNCERTAIN");
            case ACCEPTED ->
                    new PrintTransition(
                            before.status() == PrintJobStatus.PRINTING
                                    ? PrintJobStatus.PRINTING
                                    : PrintJobStatus.ACCEPTED,
                            false,
                            false,
                            "QUEUE_ONLY");
            case PRINTING -> new PrintTransition(PrintJobStatus.PRINTING, false, false, "PRINTING");
            default -> new PrintTransition(PrintJobStatus.UNKNOWN, false, false, "QUERY_REQUIRED");
        };
    }

    private static class SetHolder {
        static final java.util.Set<PrintJobStatus> TERMINAL =
                java.util.Set.of(
                        PrintJobStatus.COMPLETED,
                        PrintJobStatus.CANCELLED,
                        PrintJobStatus.FAILED,
                        PrintJobStatus.STALE);
    }
}
