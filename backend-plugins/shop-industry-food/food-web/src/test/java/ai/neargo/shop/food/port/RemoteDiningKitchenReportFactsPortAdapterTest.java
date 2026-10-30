package ai.neargo.shop.food.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.spi.DiningKitchenReportInternalApi;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportKitchenEventDayPort;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

class RemoteDiningKitchenReportFactsPortAdapterTest {
    @Test
    void transportsOriginalFsevIdentityAndReturnsOriginalDayWithoutRecomputingIt() {
        var api = mock(DiningKitchenReportInternalApi.class);
        var change = change();
        var evidence =
                new DayEvidence(
                        "DAY",
                        "POLICY",
                        0,
                        "UTC",
                        0,
                        Instant.parse("2020-01-01T00:00:00Z"),
                        Instant.parse("2020-01-02T00:00:00Z"));
        var original =
                new ReportKitchenEventDayPort.AffectedDay(
                        change,
                        LocalDate.of(2020, 1, 1),
                        evidence,
                        Instant.parse("2020-01-01T12:00:00Z"));
        when(api.affectedDay(change)).thenReturn(original);
        assertThat(new RemoteDiningKitchenReportFactsPortAdapter(api).affectedDay(change))
                .isEqualTo(original);
        verify(api).affectedDay(change);
    }

    @Test
    void unavailableOriginalOwnerDoesNotBecomeAnEmptyOrCurrentDay() {
        var api = mock(DiningKitchenReportInternalApi.class);
        when(api.affectedDay(change()))
                .thenThrow(new IllegalStateException("ORIGINAL_OWNER_UNAVAILABLE"));
        assertThatThrownBy(
                        () ->
                                new RemoteDiningKitchenReportFactsPortAdapter(api)
                                        .affectedDay(change()))
                .isInstanceOf(RuntimeException.class);
    }

    private ReportKitchenEventDayPort.Change change() {
        return new ReportKitchenEventDayPort.Change(
                new Scope("MAIN", "ENTITY", "STORE"),
                "FSEV1",
                "TASK",
                "READY",
                new BigDecimal("1.25"));
    }
}
