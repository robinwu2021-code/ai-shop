package ai.neargo.shop.print.api.dto;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintAgentDtos.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintDeviceDtos.*;
import ai.neargo.shop.print.api.dto.PrintExportDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;
import ai.neargo.shop.print.api.dto.PrintSimulationDtos.*;

import java.math.*;
import java.time.*;
import java.util.*;

public final class PrintMonitorDtos {
    private PrintMonitorDtos() {}

    public record GetHealthQuery(String storeNo) {}

    public record GetHealthVO(
            long activePrinters,
            long pendingJobs,
            long unknownJobs,
            long blockedRequests,
            Instant asOf) {}

    public record ListIncidentsQuery(
            String storeNo,
            String status,
            String severity,
            String targetNo,
            Integer page,
            Integer size) {}

    public record IncidentVO(
            String incidentNo,
            String storeNo,
            String incidentType,
            String targetType,
            String targetNo,
            String severity,
            String status,
            int occurrenceCount,
            Instant firstSeenAt,
            Instant lastSeenAt,
            long revision) {}

    public record AcknowledgeIncidentReq(Long expectedRevision, String reason) {}

    public record ListActionLogsQuery(
            String storeNo, String targetType, String targetNo, Integer page, Integer size) {}

    public record ActionLogVO(
            long storeSequence,
            String targetType,
            String targetNo,
            String action,
            String actorNo,
            Instant occurredAt) {}

    public record ListChangesQuery(String storeNo, String afterCursor, Integer limit) {}

    public record ChangePageVO(
            List<ActionLogVO> changes, long nextSequence, boolean resetRequired) {
        public ChangePageVO {
            changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        }
    }

    public record OpsReconcileReq(
            String tenantNo,
            String entityNo,
            String storeNo,
            String sourceKind,
            Instant from,
            Instant to,
            boolean dryRun,
            String reason) {}

    public record OpsGetOperationQuery(String tenantNo, String entityNo, String storeNo) {}

    public record OpsGetFleetHealthQuery(String tenantNo, String entityNo, String storeNo) {}

    public record FleetHealthVO(List<GetHealthVO> stores, Instant asOf) {
        public FleetHealthVO {
            stores = List.copyOf(Objects.requireNonNull(stores, "stores"));
        }
    }

    public record OpsListIncidentsQuery(
            String tenantNo,
            String entityNo,
            String storeNo,
            String status,
            Integer page,
            Integer size) {}
}
