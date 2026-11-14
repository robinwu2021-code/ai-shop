package ai.neargo.shop.printing.center.dispatch;

import ai.neargo.shop.print.api.*;

import java.time.Instant;
import java.util.*;

public final class PrintStateModels {
    private PrintStateModels() {}

    public record PrintStateSnapshot(
            PrintJobStatus status,
            boolean cancelRequested,
            boolean partialOutput,
            int retryCount,
            int maxRetries) {}

    public record PrintObservation(
            ObservedPrintStatus status,
            PrintEvidenceLevel evidence,
            boolean queueTerminated,
            boolean partialOutput) {}

    public record PrintTransition(
            PrintJobStatus status, boolean releaseGate, boolean safeToRetry, String reason) {}

    public record PreparedPrintAttempt(
            PrintDispatch dispatch, String connectionType, String leaseToken, Instant leaseUntil) {}

    public record ReceiptAck(String receiptNo, String status) {}

    public record PrintIncidentCommand(
            PrintScope scope,
            String type,
            String targetType,
            String targetNo,
            String severity,
            FrozenJson details) {}

    public record PrintAction(
            String operationNo,
            String targetType,
            String targetNo,
            String action,
            String actorType,
            String actorNo,
            Long beforeRevision,
            Long afterRevision,
            String beforeStatus,
            String afterStatus,
            String reason,
            FrozenJson safeDetails) {}

    public record PrintJobSnapshot(
            PrintJobStatus status, boolean required, boolean replacementResolved) {}

    public record BatchCounts(
            int expected,
            int confirmed,
            int failed,
            int unknown,
            int cancelled,
            int resolved,
            PrintBatchStatus status) {}
}
