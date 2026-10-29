package ai.neargo.shop.commerce.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.commerce.dto.ChannelStatisticsContracts;
import ai.neargo.shop.commerce.dto.ChannelStatisticsContracts.Query;
import ai.neargo.shop.commerce.mapper.CommerceStatisticsMappers.HealthRow;
import ai.neargo.shop.commerce.mapper.CommerceStatisticsMappers.StatisticsMapper;
import ai.neargo.shop.commerce.mapper.CommerceStatisticsMappers.Window;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.ChannelStatisticsService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.commerce.CommerceGateway;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Every response uses one repeatable-read snapshot; provider HTTP or business report databases are
 * never queried here.
 */
public final class ChannelStatisticsServiceImpl implements ChannelStatisticsService {
    private Supplier<Set<String>> sourceServices = Set::of;

    /**
     * Health only describes installed providers; it never probes a remote owner or invents a
     * successful capability.
     */
    public void sources(Supplier<Set<String>> available) {
        sourceServices = available;
    }

    private final StatisticsMapper mapper;
    private final Clock clock;
    private final Supplier<List<CommerceGateway>> gateways;
    private final TransactionTemplate reads;
    private final long warningSeconds;

    public ChannelStatisticsServiceImpl(
            StatisticsMapper mapper,
            PlatformTransactionManager manager,
            Clock clock,
            Supplier<List<CommerceGateway>> gateways,
            long warningSeconds) {
        if (warningSeconds < 1 || warningSeconds > 86400)
            throw new IllegalArgumentException("Backlog warning range");
        this.mapper = mapper;
        this.clock = clock;
        this.gateways = gateways;
        this.warningSeconds = warningSeconds;
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    private record Range(Instant asOf, Instant from, Instant to, Window query) {}

    private Range range(String entity, String store, Query request) {
        CommerceValidation.text(entity, 32);
        CommerceValidation.text(store, 32);
        if (request == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var now = clock.instant();
        var to = request.to() == null || request.to().isAfter(now) ? now : request.to();
        var from = request.from() == null ? to.minusSeconds(86400) : request.from();
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        if (request.providerNo() != null) CommerceValidation.text(request.providerNo(), 32);
        if (request.productCode() != null) CommerceValidation.text(request.productCode(), 32);
        if (request.type() != null) CommerceValidation.text(request.type(), 32);
        if (request.connectionNo() != null) CommerceValidation.text(request.connectionNo(), 32);
        return new Range(
                now,
                from,
                to,
                new Window(
                        entity,
                        store,
                        utc(from),
                        utc(to),
                        request.providerNo(),
                        request.productCode(),
                        request.type(),
                        request.connectionNo()));
    }

    private <T> T read(Supplier<T> work) {
        return DataScopeContext.executeWithoutScope(() -> reads.execute(tx -> work.get()));
    }

    @Override
    public ChannelStatisticsContracts.Sync syncStatistics(
            String entity, String store, Query request) {
        var range = range(entity, store, request);
        return read(
                () -> {
                    var statuses = mapper.syncStates(range.query());
                    long total = 0;
                    for (var status : statuses) total = Math.addExact(total, status.count());
                    return new ChannelStatisticsContracts.Sync(
                            range.asOf(), range.from(), range.to(), total, List.copyOf(statuses));
                });
    }

    @Override
    public PageData<ChannelStatisticsContracts.Provider> providerStatistics(
            String entity, String store, Query request, int page, int size) {
        var range = range(entity, store, request);
        long offset = CommerceValidation.offset(page, size);
        return read(
                () ->
                        PageData.of(
                                mapper.providerGroupsPage(range.query(), offset, size),
                                mapper.providerGroups(range.query()),
                                page,
                                size));
    }

    @Override
    public ChannelStatisticsContracts.Imports importStatistics(
            String entity, String store, Query request) {
        if (request != null && request.type() != null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var range = range(entity, store, request);
        return read(
                () ->
                        new ChannelStatisticsContracts.Imports(
                                range.asOf(),
                                range.from(),
                                range.to(),
                                List.copyOf(mapper.importStates(range.query())),
                                List.copyOf(mapper.eventStates(range.query())),
                                List.copyOf(mapper.receiptStates(range.query()))));
    }

    @Override
    public ChannelStatisticsContracts.Health health(
            String entity, String store, String connectionNo, String providerNo) {
        var range =
                range(entity, store, new Query(null, null, providerNo, null, null, connectionNo));
        var adapters = gateways.get();
        return read(
                () -> {
                    long total = mapper.connectionCount(range.query());
                    var rows =
                            mapper.connectionHealth(range.query()).stream()
                                    .map(row -> health(row, range.asOf(), adapters))
                                    .toList();
                    return new ChannelStatisticsContracts.Health(
                            range.asOf(), total, total > rows.size(), rows);
                });
    }

    private ChannelStatisticsContracts.ConnectionHealth health(
            HealthRow row, Instant now, List<CommerceGateway> adapters) {
        var capabilities = new TreeSet<String>();
        for (var adapter : adapters)
            if (row.profileNo().equals(adapter.profileNo()))
                adapter.capabilities().forEach(capability -> capabilities.add(capability.name()));
        var missing = new TreeSet<String>();
        var sources = sourceServices.get();
        if (row.sourceOwner() == null) missing.add("SOURCE_OWNER");
        else {
            if (capabilities.contains("CATALOG")) {
                if (!sources.contains("CATALOG:" + row.sourceOwner()))
                    missing.add("CATALOG:" + row.sourceOwner());
                if (!sources.contains("VALIDATION:" + row.sourceOwner()))
                    missing.add("VALIDATION:" + row.sourceOwner());
            }
            if (capabilities.contains("ORDER") && !sources.contains("ORDER:" + row.sourceOwner()))
                missing.add("ORDER:" + row.sourceOwner());
            if (capabilities.contains("FULFILLMENT")
                    && !sources.contains("FULFILLMENT:" + row.sourceOwner()))
                missing.add("FULFILLMENT:" + row.sourceOwner());
        }
        String status;
        if (!"ACTIVE".equals(row.connectionStatus())
                || !"ACTIVE".equals(row.accountStatus())
                || !"VERIFIED".equals(row.profileStatus())
                || !"GRANTED".equals(row.authorizationStatus())
                || row.authorizationExpiresAt() != null
                        && !instant(row.authorizationExpiresAt()).isAfter(now)) status = "BLOCKED";
        else if (capabilities.isEmpty()) status = "ADAPTER_UNAVAILABLE";
        else if (!missing.isEmpty()) status = "SOURCE_UNAVAILABLE";
        else if (row.unknownOperations() > 0
                || row.callbackConflicts() > 0
                || overdue(row.oldestOperationAt(), now)
                || overdue(row.oldestCallbackAt(), now)
                || overdue(row.oldestImportAt(), now)) status = "ATTENTION";
        else if (row.pendingOperations() > 0
                || row.pendingCallbacks() > 0
                || row.pendingImports() > 0) status = "PENDING";
        else status = "HEALTHY";
        return new ChannelStatisticsContracts.ConnectionHealth(
                row.connectionNo(),
                row.profileNo(),
                row.connectionStatus(),
                row.accountStatus(),
                row.profileStatus(),
                row.authorizationStatus(),
                instant(row.authorizationExpiresAt()),
                row.catalogConsistency(),
                row.pendingOperations(),
                row.unknownOperations(),
                row.pendingCallbacks(),
                row.callbackConflicts(),
                row.pendingImports(),
                instant(row.oldestOperationAt()),
                instant(row.oldestCallbackAt()),
                instant(row.oldestImportAt()),
                Set.copyOf(capabilities),
                Set.copyOf(missing),
                status);
    }

    private boolean overdue(LocalDateTime value, Instant now) {
        return value != null
                && value.toInstant(ZoneOffset.UTC).isBefore(now.minusSeconds(warningSeconds));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
