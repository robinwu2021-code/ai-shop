package ai.neargo.shop.print.api.dto;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintAgentDtos.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintDeviceDtos.*;
import ai.neargo.shop.print.api.dto.PrintExportDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintMonitorDtos.*;
import ai.neargo.shop.print.api.dto.PrintSimulationDtos.*;

import java.math.*;
import java.time.*;
import java.util.*;

public final class PrintProtocolDtos {
    private PrintProtocolDtos() {}

    public record DeviceClaimVO(String claimNo, List<ClaimDispatch> dispatches) {
        public DeviceClaimVO {
            dispatches = List.copyOf(Objects.requireNonNull(dispatches, "dispatches"));
        }
    }

    public record DeviceAcceptDispatchVO(boolean accepted, Instant leaseUntil, String leaseToken) {}

    public record DeviceRenewLeaseVO(Instant leaseUntil, String leaseToken) {}

    public record ClaimDispatch(
            String dispatchKey,
            String jobNo,
            String attemptNo,
            String printerNo,
            long sessionEpoch,
            long fenceEpoch,
            long connectionRevision,
            String leaseToken,
            Instant leaseUntil,
            Instant executeBefore,
            PrintLayout layout,
            String documentHash,
            FrozenJson connection,
            boolean cancelRequested) {}

    public record AgentReceiptEvent(
            String eventNo,
            String dispatchKey,
            String attemptNo,
            long fenceEpoch,
            long sequence,
            ObservedPrintStatus status,
            PrintEvidenceLevel evidenceLevel,
            String providerJobId,
            Instant occurredAt,
            FrozenJson details) {}

    public record RecoveryDispatch(
            String dispatchKey,
            String attemptNo,
            ObservedPrintStatus localStatus,
            String providerJobId) {}

    public record PrinterObservation(
            String printerNo,
            long connectionRevision,
            Instant observedAt,
            PrinterHealthStatus health,
            FrozenJson capabilitySummary) {}

    public record EventAcknowledgement(String eventNo, String status) {}

    public record RecoveryDecision(String dispatchKey, String action) {}

    public record DiscoveredPrinter(String physicalRegistrationRef, String queueName) {}

    public record DeviceCommand(
            String commandNo,
            String printerNo,
            String commandType,
            String dispatchKey,
            String attemptNo,
            long connectionRevision,
            Long fenceEpoch,
            long sessionEpoch,
            Instant expiresAt,
            FrozenJson payload) {}

    public record DeviceEnrollReq(
            String agentNo,
            String enrollmentCode,
            String clientPublicKey,
            String requestId,
            String platform,
            String softwareVersion) {}

    public record DeviceEnrollVO(String agentNo, String sealedCredential, int credentialVersion) {}

    public record DeviceOpenSessionReq(
            String requestId, String softwareVersion, Long previousSessionEpoch) {}

    public record DeviceOpenSessionVO(
            long sessionEpoch, int heartbeatIntervalSeconds, Instant serverTime) {}

    public record DeviceHeartbeatReq(
            String requestId,
            long sessionEpoch,
            List<PrinterObservation> printers,
            int localPendingCount,
            List<DiscoveredPrinter> discoveredPrinters) {
        public DeviceHeartbeatReq {
            discoveredPrinters =
                    discoveredPrinters == null ? List.of() : List.copyOf(discoveredPrinters);
            printers = List.copyOf(Objects.requireNonNull(printers, "printers"));
        }
    }

    public record DeviceHeartbeatVO(
            boolean accepted, Instant serverTime, boolean revoked, List<DeviceCommand> commands) {
        public DeviceHeartbeatVO {
            commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
        }
    }

    public record DeviceClaimReq(
            String requestId,
            long sessionEpoch,
            List<String> printerNos,
            int maxJobs,
            int waitSeconds) {
        public DeviceClaimReq {
            printerNos = List.copyOf(Objects.requireNonNull(printerNos, "printerNos"));
        }
    }

    public record DeviceAcceptDispatchReq(
            String requestId,
            long sessionEpoch,
            long fenceEpoch,
            String leaseToken,
            String documentHash) {}

    public record DeviceRenewLeaseReq(
            String requestId, long sessionEpoch, long fenceEpoch, String leaseToken) {}

    public record DeviceReportEventsReq(long sessionEpoch, List<AgentReceiptEvent> events) {
        public DeviceReportEventsReq {
            events = List.copyOf(Objects.requireNonNull(events, "events"));
        }
    }

    public record DeviceReportEventsVO(List<EventAcknowledgement> events) {
        public DeviceReportEventsVO {
            events = List.copyOf(Objects.requireNonNull(events, "events"));
        }
    }

    public record DeviceGetDispatchVO(
            ClaimDispatch dispatch, String status, boolean executable, boolean cancelRequested) {}

    public record DeviceRecoverReq(
            String requestId, long sessionEpoch, List<RecoveryDispatch> unresolvedDispatches) {
        public DeviceRecoverReq {
            unresolvedDispatches =
                    List.copyOf(
                            Objects.requireNonNull(unresolvedDispatches, "unresolvedDispatches"));
        }
    }

    public record DeviceRecoverVO(List<RecoveryDecision> results) {
        public DeviceRecoverVO {
            results = List.copyOf(Objects.requireNonNull(results, "results"));
        }
    }

    public record DeviceReportCommandResultReq(
            String requestId,
            long sessionEpoch,
            String printerNo,
            String commandType,
            String dispatchKey,
            String attemptNo,
            long connectionRevision,
            Long fenceEpoch,
            String status,
            FrozenJson result) {}

    public record DeviceReportCommandResultVO(String commandNo, String status) {}
}
