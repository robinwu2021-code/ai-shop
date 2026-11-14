package ai.neargo.shop.commerce.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Technical cohorts are created-at based; no callback or import count is described as a paid sale.
 */
public final class ChannelStatisticsContracts {
    private ChannelStatisticsContracts() {}

    public record Query(
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            String providerNo,
            String productCode,
            String type,
            String connectionNo) {}

    public record StatusCount(String status, long count) {}

    public record Sync(
            Instant asOf, Instant from, Instant to, long total, List<StatusCount> statuses) {}

    public record Provider(
            String providerNo,
            String profileNo,
            String productCode,
            String market,
            long total,
            long succeeded,
            long partial,
            long failed,
            long unknownCount,
            long pending) {}

    public record Imports(
            Instant asOf,
            Instant from,
            Instant to,
            List<StatusCount> orders,
            List<StatusCount> callbackEvents,
            List<StatusCount> callbackReceipts) {}

    public record ConnectionHealth(
            String connectionNo,
            String profileNo,
            String connectionStatus,
            String accountStatus,
            String profileStatus,
            String authorizationStatus,
            Instant authorizationExpiresAt,
            String catalogConsistency,
            long pendingOperations,
            long unknownOperations,
            long pendingCallbacks,
            long callbackConflicts,
            long pendingImports,
            Instant oldestOperationAt,
            Instant oldestCallbackAt,
            Instant oldestImportAt,
            Set<String> installedCapabilities,
            Set<String> missingSources,
            String health) {}

    public record Health(
            Instant asOf,
            long totalConnections,
            boolean truncated,
            List<ConnectionHealth> connections) {}
}
