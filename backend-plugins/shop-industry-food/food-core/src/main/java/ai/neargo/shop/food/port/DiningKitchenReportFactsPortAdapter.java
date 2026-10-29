package ai.neargo.shop.food.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.entity.FnbKitchenTask;
import ai.neargo.shop.food.entity.FnbKitchenTicket;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbServiceEvent;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTaskMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTicketMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceEventMapper;
import ai.neargo.shop.food.service.DiningKitchenReportFactsService;
import ai.neargo.shop.food.service.DiningReportFactsService;
import ai.neargo.shop.spi.report.ReportContracts.Attribution;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.MetricValue;
import ai.neargo.shop.spi.report.ReportContracts.Owner;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import ai.neargo.shop.spi.report.ReportContracts.ValueType;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Replays persisted action facts once; aggregate custody rows never double count task handovers.
 */
public final class DiningKitchenReportFactsPortAdapter implements DiningKitchenReportFactsService {
    private static final Map<String, String> METRICS =
            Map.of(
                    "START",
                    "KITCHEN_STARTED_QTY",
                    "READY",
                    "KITCHEN_READY_QTY",
                    "HANDOVER",
                    "KITCHEN_HANDED_OVER_QTY",
                    "LOSS",
                    "KITCHEN_LOSS_QTY",
                    "REMAKE",
                    "KITCHEN_REWORK_QTY");
    private final DiningReportFactsService days;
    private final ServiceEventMapper events;
    private final KitchenTaskMapper tasks;
    private final KitchenTicketMapper tickets;
    private final OrderBatchMapper batches;
    private final OrderLineMapper lines;
    private final TransactionTemplate read;

    public DiningKitchenReportFactsPortAdapter(
            DiningReportFactsService days,
            ServiceEventMapper events,
            KitchenTaskMapper tasks,
            KitchenTicketMapper tickets,
            OrderBatchMapper batches,
            OrderLineMapper lines,
            PlatformTransactionManager manager) {
        this.days = days;
        this.events = events;
        this.tasks = tasks;
        this.tickets = tickets;
        this.batches = batches;
        this.lines = lines;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public SourceKey source() {
        return new SourceKey(Owner.FOOD, "KITCHEN");
    }

    @Override
    public SourceSnapshot readSnapshot(SourceQuery query) {
        // Day owner also authorizes the actual store. Do not hold a source SQL transaction over it.
        if (!query.day()
                .accepts(days.day(query.partition().scope(), query.partition().businessDate())))
            throw new IllegalStateException("REPORT_DAY_EVIDENCE_CHANGED");
        return Objects.requireNonNull(
                read.execute(tx -> DataScopeContext.executeWithoutScope(() -> collect(query))));
    }

    private SourceSnapshot collect(SourceQuery q) {
        var s = q.partition().scope();
        var start = LocalDateTime.ofInstant(q.day().startsAt(), ZoneOffset.UTC);
        var end = LocalDateTime.ofInstant(q.day().endsAt(), ZoneOffset.UTC);
        // An entity-bounded scan deliberately fails closed at the limit, including foreign-store
        // rows.
        // Dropping unresolved joins could otherwise make a damaged source look like a verified
        // empty day.
        var rows =
                events.selectList(
                        Wrappers.<FnbServiceEvent>lambdaQuery()
                                .eq(FnbServiceEvent::getTenantNo, s.tenantNo())
                                .eq(FnbServiceEvent::getEntityNo, s.entityNo())
                                .eq(FnbServiceEvent::getDeleted, 0)
                                .ge(FnbServiceEvent::getCreatedAt, start)
                                .lt(FnbServiceEvent::getCreatedAt, end)
                                .orderByAsc(FnbServiceEvent::getEventNo)
                                .last("LIMIT " + (q.rowLimit() + 1)));
        if (rows.size() > q.rowLimit()) throw new IllegalStateException("REPORT_SOURCE_ROW_LIMIT");
        var facts = new ArrayList<MetricFact>();
        var evidence = new ArrayList<Object>();
        var seen = new HashSet<String>();
        int owned = 0;
        for (var event : rows) {
            if (event.getEventNo() == null
                    || !seen.add(event.getEventNo())
                    || event.getCreatedAt() == null
                    || event.getCreatedAt().isBefore(start)
                    || !event.getCreatedAt().isBefore(end)) invalid();
            var task =
                    tasks.selectOne(
                            Wrappers.<FnbKitchenTask>lambdaQuery()
                                    .eq(FnbKitchenTask::getTenantNo, s.tenantNo())
                                    .eq(FnbKitchenTask::getEntityNo, s.entityNo())
                                    .eq(FnbKitchenTask::getTaskNo, event.getTaskNo())
                                    .eq(FnbKitchenTask::getDeleted, 0));
            if (task == null) invalid();
            var ticket =
                    tickets.selectOne(
                            Wrappers.<FnbKitchenTicket>lambdaQuery()
                                    .eq(FnbKitchenTicket::getTenantNo, s.tenantNo())
                                    .eq(FnbKitchenTicket::getEntityNo, s.entityNo())
                                    .eq(FnbKitchenTicket::getTicketNo, task.getTicketNo())
                                    .eq(FnbKitchenTicket::getDeleted, 0));
            if (ticket == null) invalid();
            var batch =
                    batches.selectOne(
                            Wrappers.<FnbOrderBatch>lambdaQuery()
                                    .eq(FnbOrderBatch::getTenantNo, s.tenantNo())
                                    .eq(FnbOrderBatch::getEntityNo, s.entityNo())
                                    .eq(FnbOrderBatch::getBatchNo, ticket.getBatchNo())
                                    .eq(FnbOrderBatch::getDeleted, 0));
            if (batch == null) invalid();
            if (!s.storeNo().equals(batch.getStoreNo())) continue;
            owned++;
            if (event.getAction() == null || event.getQty() == null || event.getQty().signum() <= 0)
                invalid();
            evidence.add(
                    List.of(
                            event.getEventNo(),
                            event.getAction(),
                            event.getQty(),
                            event.getCreatedAt(),
                            Objects.toString(event.getOperationKey(), ""),
                            task.getTaskNo(),
                            ticket.getTicketNo(),
                            batch.getBatchNo()));
            String metric = METRICS.get(event.getAction());
            if (metric == null) continue;
            var line =
                    lines.selectOne(
                            Wrappers.<FnbOrderLine>lambdaQuery()
                                    .eq(FnbOrderLine::getTenantNo, s.tenantNo())
                                    .eq(FnbOrderLine::getEntityNo, s.entityNo())
                                    .eq(FnbOrderLine::getFoodLineNo, task.getFoodLineNo())
                                    .eq(FnbOrderLine::getDeleted, 0));
            if (line == null
                    || !batch.getBatchNo().equals(line.getBatchNo())
                    || task.getStationNo() == null
                    || task.getTaskRole() == null
                    || line.getSkuNo() == null) invalid();
            boolean originalUnit =
                    task.getComponentNo() == null
                            && line.getUnit() != null
                            && !line.getUnit().isBlank();
            var value =
                    originalUnit
                            ? MetricValue.quantity(event.getQty(), line.getUnit())
                            : new MetricValue(
                                    ValueType.QUANTITY,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    Coverage.UNKNOWN,
                                    "KITCHEN_COMPONENT_UNIT_NOT_PERSISTED",
                                    null,
                                    null,
                                    null);
            var dimensions = new HashMap<String, String>();
            dimensions.put("stationNo", task.getStationNo());
            dimensions.put("taskRole", task.getTaskRole());
            dimensions.put("componentRole", task.getComponentNo() == null ? "LINE" : "COMPONENT");
            if (task.getComponentNo() == null) dimensions.put("skuNo", line.getSkuNo());
            facts.add(
                    new MetricFact(
                            metric,
                            "KITCHEN_STATION_DAY",
                            Attribution.OCCURRENCE,
                            dimensions,
                            Map.of(),
                            value));
            evidence.add(
                    List.of(
                            line.getFoodLineNo(),
                            line.getSkuNo(),
                            Objects.toString(line.getUnit(), ""),
                            Objects.toString(task.getComponentNo(), ""),
                            task.getStationNo(),
                            task.getTaskRole()));
        }
        String digest = PricingSnapshotHash.of(List.of(q.partition(), q.day(), evidence));
        return new SourceSnapshot(
                q.partition(),
                source(),
                digest,
                digest,
                Instant.now(),
                owned,
                digest,
                owned == 0 ? Coverage.EMPTY_VERIFIED : Coverage.COMPLETE,
                null,
                facts,
                List.of());
    }

    @Override
    public AffectedDay affectedDay(Change change) {
        var scope = change.scope();
        Instant occurred =
                Objects.requireNonNull(
                        read.execute(
                                tx ->
                                        DataScopeContext.executeWithoutScope(
                                                () -> {
                                                    var event =
                                                            events.selectOne(
                                                                    Wrappers
                                                                            .<FnbServiceEvent>
                                                                                    lambdaQuery()
                                                                            .eq(
                                                                                    FnbServiceEvent
                                                                                            ::getTenantNo,
                                                                                    scope
                                                                                            .tenantNo())
                                                                            .eq(
                                                                                    FnbServiceEvent
                                                                                            ::getEntityNo,
                                                                                    scope
                                                                                            .entityNo())
                                                                            .eq(
                                                                                    FnbServiceEvent
                                                                                            ::getEventNo,
                                                                                    change
                                                                                            .eventNo())
                                                                            .eq(
                                                                                    FnbServiceEvent
                                                                                            ::getDeleted,
                                                                                    0));
                                                    if (event == null
                                                            || !change.taskNo()
                                                                    .equals(event.getTaskNo())
                                                            || !change.action()
                                                                    .equals(event.getAction())
                                                            || event.getQty() == null
                                                            || change.quantity()
                                                                            .compareTo(
                                                                                    event.getQty())
                                                                    != 0
                                                            || event.getCreatedAt() == null)
                                                        invalid();
                                                    var task =
                                                            tasks.selectOne(
                                                                    Wrappers
                                                                            .<FnbKitchenTask>
                                                                                    lambdaQuery()
                                                                            .eq(
                                                                                    FnbKitchenTask
                                                                                            ::getTenantNo,
                                                                                    scope
                                                                                            .tenantNo())
                                                                            .eq(
                                                                                    FnbKitchenTask
                                                                                            ::getEntityNo,
                                                                                    scope
                                                                                            .entityNo())
                                                                            .eq(
                                                                                    FnbKitchenTask
                                                                                            ::getTaskNo,
                                                                                    event
                                                                                            .getTaskNo())
                                                                            .eq(
                                                                                    FnbKitchenTask
                                                                                            ::getDeleted,
                                                                                    0));
                                                    if (task == null) invalid();
                                                    var ticket =
                                                            tickets.selectOne(
                                                                    Wrappers
                                                                            .<FnbKitchenTicket>
                                                                                    lambdaQuery()
                                                                            .eq(
                                                                                    FnbKitchenTicket
                                                                                            ::getTenantNo,
                                                                                    scope
                                                                                            .tenantNo())
                                                                            .eq(
                                                                                    FnbKitchenTicket
                                                                                            ::getEntityNo,
                                                                                    scope
                                                                                            .entityNo())
                                                                            .eq(
                                                                                    FnbKitchenTicket
                                                                                            ::getTicketNo,
                                                                                    task
                                                                                            .getTicketNo())
                                                                            .eq(
                                                                                    FnbKitchenTicket
                                                                                            ::getDeleted,
                                                                                    0));
                                                    if (ticket == null) invalid();
                                                    var batch =
                                                            batches.selectOne(
                                                                    Wrappers
                                                                            .<FnbOrderBatch>
                                                                                    lambdaQuery()
                                                                            .eq(
                                                                                    FnbOrderBatch
                                                                                            ::getTenantNo,
                                                                                    scope
                                                                                            .tenantNo())
                                                                            .eq(
                                                                                    FnbOrderBatch
                                                                                            ::getEntityNo,
                                                                                    scope
                                                                                            .entityNo())
                                                                            .eq(
                                                                                    FnbOrderBatch
                                                                                            ::getBatchNo,
                                                                                    ticket
                                                                                            .getBatchNo())
                                                                            .eq(
                                                                                    FnbOrderBatch
                                                                                            ::getDeleted,
                                                                                    0));
                                                    if (batch == null
                                                            || !scope.storeNo()
                                                                    .equals(batch.getStoreNo()))
                                                        invalid();
                                                    return event.getCreatedAt()
                                                            .toInstant(ZoneOffset.UTC);
                                                })));
        // All legal zones and business cutovers fit within this bounded search.
        var utcDate = occurred.atZone(ZoneOffset.UTC).toLocalDate();
        for (int offset : new int[] {0, -1, 1, -2, 2}) {
            var date = utcDate.plusDays(offset);
            DayEvidence evidence;
            try {
                evidence = days.day(scope, date);
            } catch (IllegalStateException unavailable) {
                if ("REPORT_ORIGINAL_POLICY_NOT_READY".equals(unavailable.getMessage())) continue;
                throw unavailable;
            }
            if (!occurred.isBefore(evidence.startsAt()) && occurred.isBefore(evidence.endsAt()))
                return new AffectedDay(change, date, evidence, occurred);
        }
        throw new IllegalStateException("REPORT_CHANGE_DAY_UNRESOLVED");
    }

    private static void invalid() {
        throw new IllegalStateException("REPORT_KITCHEN_SOURCE_UNRESOLVED");
    }
}
