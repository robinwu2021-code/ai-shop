package ai.neargo.shop.pay.points;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.pay.points.model.*;
import ai.neargo.shop.pay.points.query.impl.PointsObligationServiceImpl;
import ai.neargo.shop.pay.points.store.*;
import ai.neargo.shop.pay.points.store.PointsOperationsStore.Kind;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.stream.LongStream;

class PointsObligationServiceTest {
    @Test
    void root_hold_receipt_does_not_remain_an_unknown_obligation_after_child_closes() {
        var root =
                Map.<String, Object>of(
                        "id",
                        1L,
                        "state",
                        "HELD",
                        "payload_json",
                        "{\"command\":{\"targets\":[{\"subOrderNo\":\"S1\",\"storeNo\":\"ST1\",\"merchantNo\":\"M\"}]},\"outcome\":{\"points\":20,\"shares\":[{\"subOrderNo\":\"S1\",\"storeNo\":\"ST1\",\"merchantNo\":\"M\",\"points\":20}]}}");
        when(store.page("T", Kind.REDEEM, 0, 200)).thenReturn(List.of(root));
        var child =
                Map.<String, Object>of(
                        "state",
                        "CLOSED",
                        "original_points",
                        20L,
                        "payload_json",
                        "{\"subOrderNo\":\"S1\",\"share\":{\"storeNo\":\"ST1\",\"merchantNo\":\"M\"}}");
        when(read.position(PositionMove.Kind.REDEEM.table(), PointsKeys.position("R", "T", "S1")))
                .thenReturn(child);
        assertThat(new PointsObligationServiceImpl(read, store).inspect("ST1", null).clear())
                .isTrue();
        when(read.position(PositionMove.Kind.REDEEM.table(), PointsKeys.position("R", "T", "S1")))
                .thenReturn(null);
        assertThat(new PointsObligationServiceImpl(read, store).inspect("ST1", null).known())
                .isFalse();
    }

    @Test
    void positive_root_receipt_with_missing_shares_is_not_a_clearance_proof() {
        when(store.page("T", Kind.REDEEM, 0, 200))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id",
                                        1L,
                                        "state",
                                        "HELD",
                                        "payload_json",
                                        "{\"command\":{\"targets\":[]},\"outcome\":{\"points\":20,\"shares\":[]}}")));
        assertThat(new PointsObligationServiceImpl(read, store).inspect("ST1", null).known())
                .isFalse();
    }

    final PointsReadStore read = mock(PointsReadStore.class);
    final PointsOperationsStore store = mock(PointsOperationsStore.class);

    @BeforeEach
    void types() {
        when(read.types())
                .thenReturn(
                        List.of(
                                new PointType(
                                        "T",
                                        "CN",
                                        "CNY",
                                        2,
                                        new RedeemRate(2, 1),
                                        2000,
                                        365,
                                        PointType.Status.ACTIVE)));
    }

    @Test
    void store_obligations_include_only_its_original_positions_and_unpaid_receivables() {
        when(store.page("T", Kind.REDEEM, 0, 200))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id",
                                        1L,
                                        "state",
                                        "HELD",
                                        "payload_json",
                                        "{\"share\":{\"storeNo\":\"ST1\"},\"subOrderNo\":\"S1\"}")));
        when(store.page("T", Kind.RECEIVABLE, 0, 200))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id",
                                        1L,
                                        "store_no",
                                        "ST1",
                                        "state",
                                        "OPEN",
                                        "original_minor",
                                        30L,
                                        "recovered_minor",
                                        10L,
                                        "payload_json",
                                        "{}")));
        var service = new PointsObligationServiceImpl(read, store);
        assertThat(service.inspect("ST1", null).openHolds()).isEqualTo(1);
        assertThat(service.inspect("ST1", null).openRefunds()).isEqualTo(1);
        assertThat(service.inspect("ST2", null).clear()).isTrue();
    }

    @Test
    void unattributed_manual_event_and_incomplete_scan_cannot_report_clear() {
        var service = new PointsObligationServiceImpl(read, store);
        when(store.page("T", Kind.OUTBOX, 0, 200))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id",
                                        1L,
                                        "state",
                                        "MANUAL",
                                        "payload_json",
                                        "{\"kind\":\"SOURCE_FACT\",\"payload\":\"{}\"}")));
        assertThat(service.inspect("ST", null).known()).isFalse();
        when(store.page(eq("T"), eq(Kind.GRANT), anyLong(), eq(200)))
                .thenAnswer(
                        call ->
                                LongStream.rangeClosed(
                                                (long) call.getArgument(2) + 1,
                                                (long) call.getArgument(2) + 200)
                                        .mapToObj(
                                                id ->
                                                        Map.<String, Object>of(
                                                                "id",
                                                                id,
                                                                "state",
                                                                "SKIPPED",
                                                                "payload_json",
                                                                "{}"))
                                        .toList());
        assertThat(service.inspect("ST", null).clear()).isFalse();
    }

    @Test
    void binding_completion_requires_a_terminal_pay_source_proof() {
        var service = new PointsObligationServiceImpl(read, store);
        assertThat(service.inspect(null, "S").clear()).isFalse();
        when(read.lifecycle("T", "S"))
                .thenReturn(Map.of("payload_json", "{\"cancelFact\":{\"eventNo\":\"C\"}}"));
        assertThat(service.inspect(null, "S").clear()).isTrue();
    }
}
