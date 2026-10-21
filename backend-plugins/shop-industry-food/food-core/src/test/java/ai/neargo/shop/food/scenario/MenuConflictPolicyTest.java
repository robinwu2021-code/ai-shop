package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.food.dto.ExceptionInput;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.MenuScheduleVO;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.WeekScheduleInput;
import ai.neargo.shop.food.policy.MenuConflictPolicy;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuConflictPolicyTest {
    private static final Instant START = Instant.parse("2026-10-19T00:00:00Z");
    private static MenuConfigResultVO menu(String store, String zone, String group, int priority, String source,
            String from, String to, List<WeekScheduleInput> weeks, List<ExceptionInput> exceptions) {
        return new MenuConfigResultVO(new MenuVO("MENU", store, "Menu", "TIME", zone, priority, group,
                "DRAFT", 0L, 0L), new MenuScheduleVO(from, to, weeks, exceptions), null,
                List.of(new MenuContextInput(source, "DINE_IN", null, null)));
    }
    private static MenuConfigResultVO weekly(int weekday, String start, String end, int offset) {
        return menu("S", "UTC", "MEAL", 1, "POS", null, null,
                List.of(new WeekScheduleInput(weekday, start, end, offset)), List.of());
    }
    @Test void onlySameExclusivePriorityStoreTimezoneAndContextCanConflict() {
        var left = weekly(1, "10:00", "12:00", 0);
        assertThat(MenuConflictPolicy.conflicts(left, weekly(1, "11:00", "13:00", 0), START, null)).isTrue();
        for (var right : List.of(menu("S", "UTC", null, 1, "POS", null, null,
                        left.schedule().weekSchedules(), List.of()),
                menu("S", "UTC", "OTHER", 1, "POS", null, null, left.schedule().weekSchedules(), List.of()),
                menu("S", "UTC", "MEAL", 2, "POS", null, null, left.schedule().weekSchedules(), List.of()),
                menu("OTHER", "UTC", "MEAL", 1, "POS", null, null, left.schedule().weekSchedules(), List.of()),
                menu("S", "Asia/Shanghai", "MEAL", 1, "POS", null, null,
                        left.schedule().weekSchedules(), List.of()),
                menu("S", "UTC", "MEAL", 1, "H5", null, null, left.schedule().weekSchedules(), List.of()))) {
            assertThat(MenuConflictPolicy.conflicts(left, right, START, null)).isFalse();
        }
    }
    @Test void halfOpenWindowsAndFiniteEffectiveBoundsExcludeAdjacency() {
        var left = weekly(1, "10:00", "12:00", 0);
        assertThat(MenuConflictPolicy.conflicts(left, weekly(1, "12:00", "14:00", 0), START, null)).isFalse();
        assertThat(MenuConflictPolicy.conflicts(left, weekly(1, "11:00", "14:00", 0),
                Instant.parse("2026-10-19T12:00:00Z"), Instant.parse("2026-10-20T00:00:00Z"))).isFalse();
        assertThat(MenuConflictPolicy.conflicts(left, weekly(1, "11:00", "14:00", 0), START,
                Instant.parse("2026-10-19T11:00:00Z"))).isFalse();
    }
    @Test void overnightUsesStartDateAndItsPreviousDayAcrossWeekBoundary() {
        assertThat(MenuConflictPolicy.conflicts(weekly(1, "23:00", "02:00", 1),
                weekly(2, "01:00", "03:00", 0), START, null)).isTrue();
        assertThat(MenuConflictPolicy.conflicts(weekly(7, "23:00", "02:00", 1),
                weekly(1, "01:00", "03:00", 0), START, null)).isTrue();
        var sunday = menu("S", "UTC", "MEAL", 1, "POS", "2026-10-18", "2026-10-18",
                List.of(new WeekScheduleInput(7, "23:00", "02:00", 1)), List.of());
        assertThat(MenuConflictPolicy.conflicts(sunday, weekly(1, "01:00", "03:00", 0), START,
                Instant.parse("2026-10-19T02:00:00Z"))).isTrue();
    }
    @Test void validDateBoundsAndClosedExceptionOverrideWeeklyTie() {
        var weeks = List.of(new WeekScheduleInput(1, "10:00", "12:00", 0));
        var one = menu("S", "UTC", "MEAL", 1, "POS", "2026-10-19", "2026-10-19", weeks, List.of());
        var later = menu("S", "UTC", "MEAL", 1, "POS", "2026-10-26", "2026-10-26", weeks, List.of());
        assertThat(MenuConflictPolicy.conflicts(one, later, START, null)).isFalse();
        var closed = menu("S", "UTC", "MEAL", 1, "POS", "2026-10-19", "2026-10-19", weeks,
                List.of(new ExceptionInput("2026-10-19", "CLOSED", null, null, 0)));
        assertThat(MenuConflictPolicy.conflicts(one, closed, START, null)).isFalse();
    }
    @Test void farFutureOpenExceptionIsNotLostToAnArbitraryHorizon() {
        var exceptional = menu("S", "UTC", "MEAL", 1, "POS", null, null, List.of(),
                List.of(new ExceptionInput("9999-12-31", "OPEN", "10:00", "11:00", 0)));
        var everyDay = menu("S", "UTC", "MEAL", 1, "POS", "9999-12-31", "9999-12-31", List.of(),
                List.of(new ExceptionInput("9999-12-31", "OPEN", "10:30", "12:00", 0)));
        assertThat(MenuConflictPolicy.conflicts(exceptional, everyDay, START, null)).isTrue();
        assertThat(MenuConflictPolicy.conflicts(exceptional, everyDay, START,
                Instant.parse("2030-01-01T00:00:00Z"))).isFalse();
        var empty = menu("S", "UTC", "MEAL", 1, "POS", null, null, List.of(), List.of());
        assertThat(MenuConflictPolicy.conflicts(exceptional, empty, START, null)).isFalse();
    }
    @Test void dstGapsDoNotInventWindowsAndSecondFoldOccurrenceIsComparedAsAnInstant() {
        var beforeGap = menu("S", "America/New_York", "MEAL", 1, "POS", "2026-03-08", "2026-03-08",
                List.of(new WeekScheduleInput(7, "01:30", "02:30", 0)), List.of());
        var afterGap = menu("S", "America/New_York", "MEAL", 1, "POS", "2026-03-08", "2026-03-08",
                List.of(new WeekScheduleInput(7, "03:00", "03:30", 0)), List.of());
        assertThat(MenuConflictPolicy.conflicts(beforeGap, afterGap, Instant.parse("2026-03-08T00:00:00Z"),
                Instant.parse("2026-03-09T00:00:00Z"))).isFalse();
        var foldOne = menu("S", "America/New_York", "MEAL", 1, "POS", "2026-11-01", "2026-11-01",
                List.of(new WeekScheduleInput(7, "01:15", "01:45", 0)), List.of());
        var foldTwo = menu("S", "America/New_York", "MEAL", 1, "POS", "2026-11-01", "2026-11-01",
                List.of(new WeekScheduleInput(7, "01:30", "02:00", 0)), List.of());
        assertThat(MenuConflictPolicy.conflicts(foldOne, foldTwo, Instant.parse("2026-11-01T06:00:00Z"),
                Instant.parse("2026-11-01T07:00:00Z"))).isTrue();
    }
}
