package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.trade.order.mapper.*;
import ai.neargo.shop.trade.order.points.*;
import ai.neargo.shop.trade.order.points.impl.OrderPointsRecoveryServiceImpl;
import ai.neargo.shop.trade.service.AfterSaleService;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

class PointsOrderRecoveryFlowTest {
    private final PointsOrderPlacementCoordinator placements =
            mock(PointsOrderPlacementCoordinator.class);
    private final OrderPointsRefundCoordinator refunds = mock(OrderPointsRefundCoordinator.class);
    private final OrderPointsFactService facts = mock(OrderPointsFactService.class);
    private final OrderPointsOutboxConsumer delivery = mock(OrderPointsOutboxConsumer.class);
    private final OrderPointsClosureFlowService closure = mock(OrderPointsClosureFlowService.class);
    private final OrderPointsBindingService bindings = mock(OrderPointsBindingService.class);
    private final OrderPointsBindingMapper bindingRows = mock(OrderPointsBindingMapper.class);
    private final OrderPointsFactMapper factRows = mock(OrderPointsFactMapper.class);
    private final OrderPointsRefundBindingMapper refundRows =
            mock(OrderPointsRefundBindingMapper.class);
    private final AfterSaleService afterSales = mock(AfterSaleService.class);
    private final PointsPaymentSnapshotPort payments = mock(PointsPaymentSnapshotPort.class);
    private final OrderPointsRecoveryService service =
            new OrderPointsRecoveryServiceImpl(
                    placements,
                    refunds,
                    facts,
                    delivery,
                    closure,
                    bindings,
                    bindingRows,
                    factRows,
                    refundRows,
                    afterSales,
                    payments);

    @Test
    void each_queue_retries_original_identity_and_one_failure_does_not_starve_the_rest() {
        when(placements.pending(1)).thenReturn(List.of("O"));
        when(refunds.pending(1)).thenReturn(List.of("A"));
        when(facts.pending(1)).thenReturn(List.of("E"));
        when(bindingRows.scanSubOrders("", 1)).thenReturn(List.of("S"));
        doThrow(new IllegalArgumentException("POINTS_HOLD_UNKNOWN")).when(placements).resume("O");
        var at = Instant.ofEpochMilli(1000);
        var lifecycle =
                new PointsFactPort.LifecycleFact(
                        "E",
                        "HASH",
                        "S",
                        "U",
                        "TYPE",
                        0,
                        PointsFactPort.FactKind.CANCELLED,
                        0,
                        0,
                        List.of(),
                        at);
        when(facts.find("E"))
                .thenReturn(
                        Optional.of(
                                new PointsOrderEvent(
                                        "O",
                                        "S",
                                        "M",
                                        "ST",
                                        "U",
                                        "TYPE",
                                        "CNY",
                                        0,
                                        at,
                                        new SourceFactsPort.Fact(
                                                1, lifecycle, null, null, null, null))));
        var report = service.recover(4);
        assertThat(report.attempted()).isEqualTo(4);
        assertThat(report.completed()).isEqualTo(3);
        assertThat(report.failures())
                .containsExactly(
                        new OrderPointsRecoveryService.Failure("ORDER:O", "POINTS_HOLD_UNKNOWN"));
        verify(afterSales).resumeRefund("A", "SYSTEM");
        verify(delivery).deliver("E", "S");
        verify(closure).finalizeSubOrder("S");
        verify(closure).closeRefundResponsibility("S");
    }

    @Test
    void run_is_bounded_even_with_all_queues_backlogged() {
        when(placements.pending(1)).thenReturn(List.of("O"));
        var report = service.recover(1);
        assertThat(report.attempted()).isEqualTo(1);
        verify(refunds, never()).pending(anyInt());
        verify(facts, never()).pending(anyInt());
        verify(bindingRows, never()).scanSubOrders(anyString(), anyInt());
    }

    @Test
    void repeated_single_item_runs_visit_every_recovery_queue() {
        when(placements.pending(1)).thenReturn(List.of("O"));
        when(refunds.pending(1)).thenReturn(List.of("A"));
        when(facts.pending(1)).thenReturn(List.of("E"));
        when(bindingRows.scanSubOrders("", 1)).thenReturn(List.of("S"));
        when(facts.find("E"))
                .thenReturn(
                        Optional.of(
                                new PointsOrderEvent(
                                        "O",
                                        "S",
                                        "M",
                                        "ST",
                                        "U",
                                        "TYPE",
                                        "CNY",
                                        0,
                                        Instant.ofEpochMilli(1000),
                                        new SourceFactsPort.Fact(
                                                1,
                                                new PointsFactPort.LifecycleFact(
                                                        "E",
                                                        "HASH",
                                                        "S",
                                                        "U",
                                                        "TYPE",
                                                        0,
                                                        PointsFactPort.FactKind.CANCELLED,
                                                        0,
                                                        0,
                                                        List.of(),
                                                        Instant.ofEpochMilli(1000)),
                                                null,
                                                null,
                                                null,
                                                null))));

        for (int i = 0; i < 4; i++) assertThat(service.recover(1).attempted()).isEqualTo(1);

        verify(placements).resume("O");
        verify(afterSales).resumeRefund("A", "SYSTEM");
        verify(delivery).deliver("E", "S");
        verify(closure).finalizeSubOrder("S");
    }

    @Test
    void inspection_reports_versions_original_hold_and_pending_codes_without_payloads() {
        var admission =
                new OrderPointsBindingService.Admission(
                        "REQ", "O", "S", "U", "M", "ST", "TYPE", "CNY", "ONLINE", 0, true, true,
                        null);
        when(bindings.find("S"))
                .thenReturn(
                        new OrderPointsBindingService.Binding(
                                "B", admission, OrderPointsBindingService.Phase.BOUND, 2, "H"));
        when(factRows.pendingSub("S")).thenReturn(2L);
        when(refundRows.forSubOrder("S"))
                .thenReturn(List.of(java.util.Map.of("phase", "COMPENSATING")));
        when(payments.verified("O")).thenReturn(Optional.empty());
        var inspection = service.inspect("S");
        assertThat(inspection.bindingVersion()).isEqualTo(2);
        assertThat(inspection.originalHoldNo()).isEqualTo("H");
        assertThat(inspection.pendingSourceEvents()).isEqualTo(2);
        assertThat(inspection.differences())
                .containsExactly("PLACEMENT_MISSING", "SOURCE_EVENT_PENDING", "REFUND_PENDING");
    }
}
