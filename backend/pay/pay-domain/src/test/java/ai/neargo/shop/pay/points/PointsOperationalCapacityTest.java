package ai.neargo.shop.pay.points;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.pay.points.event.PointsAttentionService;
import ai.neargo.shop.pay.points.model.FundPool;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.query.impl.PointsStatsServiceImpl;
import ai.neargo.shop.pay.points.recon.impl.PointsReconServiceImpl;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsFactStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore.Kind;
import ai.neargo.shop.pay.points.store.PointsReadStore;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;

class PointsOperationalCapacityTest {
    @Test
    void incomplete_deep_reconciliation_can_never_report_balanced() {
        var config = mock(PointsConfigurationStore.class);
        var store = mock(PointsOperationsStore.class);
        var read = mock(PointsReadStore.class);
        var alerts = mock(PointsAttentionService.class);
        when(read.type("T"))
                .thenReturn(
                        Optional.of(
                                new PointType(
                                        "T",
                                        "CN",
                                        "CNY",
                                        2,
                                        new RedeemRate(1, 1),
                                        10000,
                                        30,
                                        PointType.Status.ACTIVE)));
        when(read.pool("T")).thenReturn(new FundPool("T", "CNY", 0, 0, 0, 0, 0, 0, 0));
        when(store.page(eq("T"), eq(Kind.ACCOUNT), anyLong(), eq(200)))
                .thenAnswer(call -> page(call.getArgument(2)));
        var report = new PointsReconServiceImpl(config, store, read, alerts).scan("T", 0);
        assertThat(report.complete()).isFalse();
        assertThat(report.balanced()).isFalse();
        assertThat(report.differences()).anyMatch(d -> d.code().equals("SCAN_LIMIT"));
    }

    @Test
    void oversized_statistics_do_not_acknowledge_the_original_posting_event() {
        var config = mock(PointsConfigurationStore.class);
        var store = mock(PointsOperationsStore.class);
        var read = mock(PointsReadStore.class);
        var facts = mock(PointsFactStore.class);
        var day = LocalDate.of(2026, 10, 16);
        when(store.postingEvents("T", 1))
                .thenReturn(
                        List.of(Map.of("id", 1L, "version", 0L, "voucher_at", day.atStartOfDay())));
        when(store.vouchers(eq("T"), eq(day), anyLong(), eq(200)))
                .thenAnswer(call -> page(call.getArgument(2)));
        var stats = new PointsStatsServiceImpl(config, store, read, facts);
        assertThatThrownBy(() -> stats.project("T", 1)).hasMessage("POINTS_STATS_SCAN_LIMIT");
        verify(store, never()).projected(anyLong(), anyLong());
    }

    private List<Map<String, Object>> page(long after) {
        return LongStream.rangeClosed(after + 1, after + 200)
                .mapToObj(id -> Map.<String, Object>of("id", id))
                .toList();
    }
}
