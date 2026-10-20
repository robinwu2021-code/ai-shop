package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.policy.ServiceCalendarPolicy.DayRule;
import ai.neargo.shop.food.policy.ServiceCalendarPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceCalendarPolicyTest {
    private final StoreOperationPolicyResultVO policy =
            new StoreOperationPolicyResultVO("P1", "Asia/Shanghai", 240, "PREPAY", List.of(), 1);
    private final LocalDate day = LocalDate.of(2026, 10, 20);
    @Test void cutoverAssignsEarlyMorningToPreviousBusinessDay() {
        assertThat(ServiceCalendarPolicy.businessDate(Instant.parse("2026-10-20T18:00:00Z"), policy)).isEqualTo(day);
        assertThat(ServiceCalendarPolicy.businessDate(Instant.parse("2026-10-20T20:00:00Z"), policy))
                .isEqualTo(day.plusDays(1));
    }
    @Test void overnightCalendarAndSharedHoursAreAnIntersection() {
        var rule = new DayRule(day, "OPEN", List.of("21:00-01:00"));
        assertThat(accept("ACTIVE", "22:00-02:00", "2026-10-20T13:30:00Z", rule)).isFalse();
        assertThat(accept("ACTIVE", "22:00-02:00", "2026-10-20T14:00:00Z", rule)).isTrue();
        assertThat(accept("ACTIVE", "22:00-02:00", "2026-10-20T16:30:00Z", rule)).isTrue();
        assertThat(accept("ACTIVE", "22:00-02:00", "2026-10-20T17:00:00Z", rule)).isFalse();
    }
    @Test void closedDayOverridesStoreHoursAndNoExceptionUsesExistingHours() {
        assertThat(accept("ACTIVE", "00:00-00:00", "2026-10-20T16:30:00Z",
                new DayRule(day, "CLOSED", List.of()))).isFalse();
        assertThat(accept("ACTIVE", "22:00-02:00", "2026-10-20T16:30:00Z", null)).isTrue();
        assertThat(accept("READONLY", "00:00-00:00", "2026-10-20T16:30:00Z", null)).isFalse();
        assertThat(accept("ACTIVE", "午间营业", "2026-10-20T16:30:00Z", null)).isFalse();
    }
    @Test void normalizesEquivalentWindowsAndRejectsInvalidCommands() {
        assertThat(ServiceCalendarPolicy.normalize("OPEN", List.of("22:00-02:00", "08:00-10:00", "22:00-02:00")))
                .containsExactly("08:00-10:00", "22:00-02:00");
        for (var windows : List.of(List.<String>of(), List.of("25:00-26:00"), List.of("8:0-10:00"))) {
            assertThatThrownBy(() -> ServiceCalendarPolicy.normalize("OPEN", windows)).isInstanceOf(BizException.class);
        }
        assertThatThrownBy(() -> ServiceCalendarPolicy.normalize("CLOSED", List.of("22:00-02:00")))
                .isInstanceOf(BizException.class);
    }
    private boolean accept(String status, String hours, String at, DayRule rule) {
        return ServiceCalendarPolicy.acceptsAt(status, hours, policy, Instant.parse(at), rule);
    }
}
