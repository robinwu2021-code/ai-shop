package ai.neargo.shop.print.api.dto;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintAgentDtos.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintDeviceDtos.*;
import ai.neargo.shop.print.api.dto.PrintExportDtos.*;
import ai.neargo.shop.print.api.dto.PrintMonitorDtos.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;
import ai.neargo.shop.print.api.dto.PrintSimulationDtos.*;

import java.math.*;
import java.time.*;
import java.util.*;

public final class PrintJobDtos {
    private PrintJobDtos() {}

    public record JobRevision(String jobNo, Long expectedRevision) {}

    public record ListRequestsQuery(
            String storeNo,
            String orderNo,
            String subOrderNo,
            String scene,
            String status,
            Instant from,
            Instant to,
            Integer page,
            Integer size) {}

    public record PrintRequestVO(
            String requestNo,
            String storeNo,
            String orderNo,
            String subOrderNo,
            String scene,
            String status,
            String blockCode,
            long revision,
            Instant createdAt) {}

    public record RequestDetailVO(PrintRequestVO request, List<String> batchNos) {
        public RequestDetailVO {
            batchNos = List.copyOf(Objects.requireNonNull(batchNos, "batchNos"));
        }
    }

    public record SubmitPrintRequestReq(
            String storeNo,
            PrintSourceReference sourceRef,
            String scene,
            String expectedSourceRevision,
            PrintMode printMode,
            PrintOutputOptions outputOptions) {}

    public record RetryGenerationReq(Long expectedRevision, String reason) {}

    public record PrintBatchVO(
            String batchNo,
            String requestNo,
            String status,
            int expectedCount,
            int confirmedCount,
            int unknownCount,
            int failedCount,
            int resolvedCount,
            long revision) {}

    public record ListJobsQuery(
            String storeNo,
            String requestNo,
            String batchNo,
            String orderNo,
            String scene,
            String status,
            String jobKind,
            String printerNo,
            Instant from,
            Instant to,
            Integer page,
            Integer size) {}

    public record PrintJobVO(
            String jobNo,
            String requestNo,
            String batchNo,
            String storeNo,
            String scene,
            String printerNo,
            String status,
            String jobKind,
            String rootJobNo,
            String parentJobNo,
            String replacementJobNo,
            String resolutionStatus,
            long revision,
            Instant createdAt,
            String documentKey,
            int copyIndex,
            boolean required,
            int priority,
            int retryCount,
            int maxRetryCount,
            String resultEvidence,
            Instant confirmedAt,
            Instant cancelRequestedAt,
            PrintOperationError lastError,
            Set<String> allowedActions,
            List<String> blockers,
            Instant updatedAt,
            Instant asOf,
            String orderNo,
            String subOrderNo) {
        public PrintJobVO {
            allowedActions = Set.copyOf(allowedActions);
            blockers = List.copyOf(blockers);
        }
    }

    public record PrintJobView(
            PrintJobVO job,
            String currentAttemptNo,
            String resultEvidence,
            String lastErrorCode,
            Instant confirmedAt) {}

    public record ListAttemptsQuery(Integer page, Integer size) {}

    public record PrintAttemptVO(
            String attemptNo,
            String jobNo,
            String status,
            String providerJobId,
            long fenceEpoch,
            Instant submittedAt,
            Instant finishedAt) {}

    public record ListReceiptsQuery(Integer page, Integer size) {}

    public record PrintReceiptVO(
            String receiptNo,
            String attemptNo,
            String reportedStatus,
            String evidenceLevel,
            String status,
            Instant receivedAt) {}

    public record DocumentPreviewVO(
            String jobNo, PrintDocument document, PrintLayout layout, String documentHash) {}

    public record GetOrderSummaryQuery(String storeNo) {}

    public record OrderPrintSummaryVO(
            String generationState,
            long expectedCount,
            long confirmedCount,
            long replacementResolvedCount,
            long unknownCount,
            long failedCount,
            long exportCount,
            String latestExportNo,
            String latestExportStatus,
            Instant asOf,
            List<PrintFactRef> sourceFacts) {}

    public record ReprintReq(
            Long expectedRevision,
            String reason,
            String targetPrinterNo,
            boolean riskAcknowledged) {}

    public record BatchReprintReq(
            String storeNo,
            List<JobRevision> jobs,
            String reason,
            String targetPrinterNo,
            boolean riskAcknowledged) {
        public BatchReprintReq {
            jobs = List.copyOf(Objects.requireNonNull(jobs, "jobs"));
        }
    }

    public record ExtraCopiesReq(
            Long expectedRevision, Integer copies, String reason, String targetPrinterNo) {}

    public record RetryJobReq(Long expectedRevision, String reason) {}

    public record CancelJobReq(Long expectedRevision, String reason) {}

    public record QueryResultReq(Long expectedRevision) {}

    public record ConfirmManuallyReq(
            Long expectedRevision,
            String outcome,
            String reason,
            String evidenceRef,
            boolean queueTerminatedConfirmed) {}

    public record ReleaseStaleReq(
            Long expectedRevision, String reason, String expectedSourceRevision) {}

    public record ResolveDeviceUncertaintyReq(
            Long expectedRevision,
            String activeAttemptNo,
            String action,
            String reason,
            String evidenceRef,
            boolean oldExecutorStopped) {}
}
