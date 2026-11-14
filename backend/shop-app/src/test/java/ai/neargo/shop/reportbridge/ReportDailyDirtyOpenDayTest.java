package ai.neargo.shop.reportbridge;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import ai.neargo.shop.report.config.ReportStoreProperties;
import ai.neargo.shop.report.dto.ReportRows.CursorRow;
import ai.neargo.shop.report.dto.ReportRows.DirtyRow;
import ai.neargo.shop.report.service.ReportStorageService;
import ai.neargo.shop.report.support.ReportMetricCalculator;
import ai.neargo.shop.report.support.ReportMetricCatalog;
import ai.neargo.shop.reportbridge.service.ReportDailyService.Outcome;
import ai.neargo.shop.reportbridge.service.ReportSourceCollectionService;
import ai.neargo.shop.reportbridge.service.impl.ReportDailyServiceImpl;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportDayPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 营业日还没结束时，脏日要「延后」，不是「失败」。
 *
 * <p>当天营业中的订单/售后事件会把当天标脏，{@code readyAfter} 是事件时间，早已过去；
 * 但当天还没收市。此前脏日循环只看 {@code readyAfter}，于是去重建、被
 * {@code REPORT_DAY_NOT_READY} 拒掉、记一次失败 —— 04:30 的日报每天都报部分失败，
 * 真失败被淹没。规划循环早就按 {@code endsAt + buffer} 延后了，这里与它对齐。
 */
@DisplayName("日报：未收市的脏日延后而不是计失败")
class ReportDailyDirtyOpenDayTest {

    @Test
    void openBusinessDayIsDeferredNotFailed() {
        Instant now = Instant.parse("2026-10-25T02:00:00Z");
        LocalDate today = LocalDate.of(2026, 10, 25);
        Scope scope = new Scope("T1", "E1", "S1");
        Partition partition = new Partition(scope, today, 1);

        ReportStorageService storage = mock(ReportStorageService.class);
        ReportDayPort days = mock(ReportDayPort.class);
        when(storage.enabledScopesForWorker(anyInt())).thenReturn(List.of(
                new CursorRow(partition, today, today.minusDays(1), true, true, 1)));
        when(storage.recoverableBuilds(any(), anyInt(), anyInt())).thenReturn(List.of());
        when(storage.pendingDirty(any(), anyInt(), anyInt())).thenReturn(List.of(
                new DirtyRow(partition, 1, 0, now.minus(1, ChronoUnit.HOURS))));
        // 当天 16:00Z 才收市
        when(days.day(any(), any())).thenReturn(new DayEvidence("D1", "P1", 1, "Asia/Shanghai", 0,
                Instant.parse("2026-10-24T16:00:00Z"), Instant.parse("2026-10-25T16:00:00Z")));

        var service = new ReportDailyServiceImpl(storage, mock(ReportSourceCollectionService.class), days,
                mock(ReportMetricCatalog.class), mock(ReportMetricCalculator.class),
                new ReportStoreProperties.Food(), Clock.fixed(now, ZoneOffset.UTC));

        Outcome outcome = service.runDaily("RUN1", today);

        assertThat(outcome.failed()).isZero();
        assertThat(outcome.deferred()).isGreaterThanOrEqualTo(1);
        verify(storage, never()).beginBuild(any(), any());
    }
}
