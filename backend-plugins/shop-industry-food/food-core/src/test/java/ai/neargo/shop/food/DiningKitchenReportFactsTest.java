package ai.neargo.shop.food;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import ai.neargo.shop.food.port.DiningKitchenReportFactsPortAdapter;
import ai.neargo.shop.food.service.DiningReportFactsService;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportKitchenEventDayPort;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

class DiningKitchenReportFactsTest {
    private final DiningReportFactsService days = mock(DiningReportFactsService.class);
    private final ServiceEventMapper events = mock(ServiceEventMapper.class);
    private final KitchenTaskMapper tasks = mock(KitchenTaskMapper.class);
    private final KitchenTicketMapper tickets = mock(KitchenTicketMapper.class);
    private final OrderBatchMapper batches = mock(OrderBatchMapper.class);
    private final OrderLineMapper lines = mock(OrderLineMapper.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final DayEvidence day =
            new DayEvidence(
                    "DAY",
                    "POLICY",
                    0,
                    "UTC",
                    0,
                    Instant.parse("2026-10-01T00:00:00Z"),
                    Instant.parse("2026-10-02T00:00:00Z"));
    private final SourceQuery query =
            new SourceQuery(
                    new Partition(new Scope("MAIN", "E1", "S1"), LocalDate.of(2026, 10, 1), 1),
                    day,
                    "WORKER",
                    List.of(),
                    100);

    private DiningKitchenReportFactsPortAdapter source() {
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(days.day(any(), any())).thenReturn(day);
        return new DiningKitchenReportFactsPortAdapter(
                days, events, tasks, tickets, batches, lines, manager);
    }

    private FnbServiceEvent event(String number, String action) {
        var row = new FnbServiceEvent();
        row.setEventNo(number);
        row.setTaskNo("TASK");
        row.setAction(action);
        row.setQty(new BigDecimal("1.25"));
        row.setCreatedAt(LocalDateTime.of(2026, 10, 1, 12, 0));
        return row;
    }

    private FnbKitchenTask original(String store) {
        var task = new FnbKitchenTask();
        task.setTaskNo("TASK");
        task.setTicketNo("TICKET");
        task.setFoodLineNo("LINE");
        task.setStationNo("STATION");
        task.setTaskRole("MAKE");
        var ticket = new FnbKitchenTicket();
        ticket.setTicketNo("TICKET");
        ticket.setBatchNo("BATCH");
        var batch = new FnbOrderBatch();
        batch.setBatchNo("BATCH");
        batch.setStoreNo(store);
        var line = new FnbOrderLine();
        line.setFoodLineNo("LINE");
        line.setBatchNo("BATCH");
        line.setSkuNo("SKU");
        line.setUnit("PORTION");
        when(tasks.selectOne(any())).thenReturn(task);
        when(tickets.selectOne(any())).thenReturn(ticket);
        when(batches.selectOne(any())).thenReturn(batch);
        when(lines.selectOne(any())).thenReturn(line);
        return task;
    }

    @Test
    void correctionUsesOriginalFsevTimeRatherThanOrderDateOrTransportTime() {
        var source = source();
        original("S1");
        when(events.selectOne(any())).thenReturn(event("FSEV1", "READY"));
        var change =
                new ReportKitchenEventDayPort.Change(
                        query.partition().scope(),
                        "FSEV1",
                        "TASK",
                        "READY",
                        new BigDecimal("1.250000"));
        var affected = source.affectedDay(change);
        assertThat(affected.businessDate()).isEqualTo(query.partition().businessDate());
        assertThat(affected.day()).isEqualTo(day);
        assertThat(affected.originalOccurredAt()).isEqualTo(Instant.parse("2026-10-01T12:00:00Z"));
        assertThat(affected.original()).isEqualTo(change);
    }

    @Test
    void correctionRejectsForgedStoreActionAndQuantity() {
        var source = source();
        original("S2");
        when(events.selectOne(any())).thenReturn(event("FSEV1", "READY"));
        var change =
                new ReportKitchenEventDayPort.Change(
                        query.partition().scope(),
                        "FSEV1",
                        "TASK",
                        "READY",
                        new BigDecimal("1.25"));
        assertThatThrownBy(() -> source.affectedDay(change))
                .hasMessage("REPORT_KITCHEN_SOURCE_UNRESOLVED");
        original("S1");
        var forged =
                new ReportKitchenEventDayPort.Change(
                        change.scope(), "FSEV1", "TASK", "HANDOVER", change.quantity());
        assertThatThrownBy(() -> source.affectedDay(forged))
                .hasMessage("REPORT_KITCHEN_SOURCE_UNRESOLVED");
        var wrongQty =
                new ReportKitchenEventDayPort.Change(
                        change.scope(), "FSEV1", "TASK", "READY", BigDecimal.TEN);
        assertThatThrownBy(() -> source.affectedDay(wrongQty))
                .hasMessage("REPORT_KITCHEN_SOURCE_UNRESOLVED");
    }

    @Test
    void countsTaskHandoverOnceWithoutCountingCustodySummary() {
        var source = source();
        original("S1");
        when(events.selectList(any()))
                .thenReturn(List.of(event("E1", "CUSTODY_HANDOVER"), event("E2", "HANDOVER")));
        var result = source.readSnapshot(query);
        assertThat(result.facts())
                .singleElement()
                .satisfies(
                        fact -> {
                            assertThat(fact.metricCode()).isEqualTo("KITCHEN_HANDED_OVER_QTY");
                            assertThat(fact.value().quantityValue()).isEqualByComparingTo("1.25");
                            assertThat(fact.value().unit()).isEqualTo("PORTION");
                        });
        assertThat(result.rowCount()).isEqualTo(2);
    }

    @Test
    void componentNeverBorrowsParentSkuOrUnit() {
        var source = source();
        original("S1").setComponentNo("COMPONENT");
        when(events.selectList(any())).thenReturn(List.of(event("E1", "READY")));
        assertThat(source.readSnapshot(query).facts())
                .singleElement()
                .satisfies(
                        fact -> {
                            assertThat(fact.value().coverage()).isEqualTo(Coverage.UNKNOWN);
                            assertThat(fact.value().quantityValue()).isNull();
                            assertThat(fact.value().unit()).isNull();
                            assertThat(fact.dimensions()).doesNotContainKey("skuNo");
                        });
    }

    @Test
    void duplicateEventsAndExclusiveEndAreRejected() {
        var source = source();
        original("S1");
        var row = event("E1", "START");
        when(events.selectList(any())).thenReturn(List.of(row, row));
        assertThatThrownBy(() -> source.readSnapshot(query))
                .hasMessage("REPORT_KITCHEN_SOURCE_UNRESOLVED");
        row.setCreatedAt(LocalDateTime.of(2026, 10, 2, 0, 0));
        when(events.selectList(any())).thenReturn(List.of(row));
        assertThatThrownBy(() -> source.readSnapshot(query))
                .hasMessage("REPORT_KITCHEN_SOURCE_UNRESOLVED");
    }

    @Test
    void foreignStoreContributesNoFactsOrEvidence() {
        var source = source();
        original("S2");
        when(events.selectList(any())).thenReturn(List.of(event("E1", "LOSS")));
        var result = source.readSnapshot(query);
        assertThat(result.coverage()).isEqualTo(Coverage.EMPTY_VERIFIED);
        assertThat(result.facts()).isEmpty();
        assertThat(result.rowCount()).isZero();
    }

    @Test
    void changedOriginalDayStopsBeforeSourceRead() {
        var source = source();
        when(days.day(any(), any()))
                .thenReturn(new DayEvidence("D2", "P2", 0, "UTC", 0, day.startsAt(), day.endsAt()));
        assertThatThrownBy(() -> source.readSnapshot(query))
                .hasMessage("REPORT_DAY_EVIDENCE_CHANGED");
        verifyNoInteractions(events, tasks, tickets, batches, lines);
    }
}
