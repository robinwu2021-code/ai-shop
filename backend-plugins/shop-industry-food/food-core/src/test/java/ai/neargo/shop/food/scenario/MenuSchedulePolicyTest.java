package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.ExceptionInput;
import ai.neargo.shop.food.dto.MenuScheduleVO;
import ai.neargo.shop.food.dto.ReplaceMenuScheduleReq;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.dto.WeekScheduleInput;
import ai.neargo.shop.food.policy.MenuSchedulePolicy;
import ai.neargo.shop.food.policy.MenuScheduleDefinitions;
import ai.neargo.shop.food.policy.ServiceCalendarPolicy.DayRule;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuSchedulePolicyTest {
    static WeekScheduleInput week(int day, String start, String end, int offset) {
        return new WeekScheduleInput(day, start, end, offset);
    }
    static MenuScheduleVO rules(List<WeekScheduleInput> weeks, List<ExceptionInput> exceptions) {
        return MenuScheduleDefinitions.normalize(new ReplaceMenuScheduleReq("SCHEDULE", 0L, weeks, exceptions,
                "2026-10-01", "2026-10-31"));
    }
    static boolean allows(MenuScheduleVO rules, String time, String hours, DayRule day) {
        return MenuSchedulePolicy.acceptsAt(rules, "ACTIVE", hours,
                new StoreOperationPolicyResultVO("P", "Asia/Shanghai", 240, "PREPAY", List.of(), 0),
                Instant.parse(time), day);
    }
    @Test void exactEndAndOvernightUseLocalStartDateAndDateRange() {
        var breakfast = rules(List.of(week(1, "07:00", "11:00", 0)), List.of());
        assertThat(allows(breakfast, "2026-10-19T02:59:59Z", "00:00-00:00", null)).isTrue();
        assertThat(allows(breakfast, "2026-10-19T03:00:00Z", "00:00-00:00", null)).isFalse();
        var night = rules(List.of(week(1, "22:00", "02:00", 1)), List.of());
        assertThat(allows(night, "2026-10-19T17:59:59Z", "00:00-00:00", null)).isTrue();
        assertThat(allows(night, "2026-10-19T18:00:00Z", "00:00-00:00", null)).isFalse();
        var end = new MenuScheduleVO("2026-10-19", "2026-10-19", night.weekSchedules(), List.of());
        assertThat(allows(end, "2026-10-19T17:00:00Z", "00:00-00:00", null)).isTrue();
        assertThat(allows(end, "2026-10-26T17:00:00Z", "00:00-00:00", null)).isFalse();
    }
    @Test void exceptionReplacesAnchorDayAndCannotWidenStoreHoursOrBusinessCalendar() {
        var weekly = List.of(week(1, "22:00", "02:00", 1));
        var closed = rules(weekly, List.of(new ExceptionInput("2026-10-19", "CLOSED", null, null, 0)));
        assertThat(allows(closed, "2026-10-19T17:00:00Z", "00:00-00:00", null)).isFalse();
        var open = rules(weekly, List.of(new ExceptionInput("2026-10-19", "OPEN", "08:00", "12:00", 0)));
        assertThat(allows(open, "2026-10-19T01:00:00Z", "00:00-00:00", null)).isTrue();
        assertThat(allows(open, "2026-10-19T17:00:00Z", "00:00-00:00", null)).isFalse();
        assertThat(allows(open, "2026-10-19T01:00:00Z", "10:00-11:00", null)).isFalse();
        assertThat(allows(open, "2026-10-19T01:00:00Z", "00:00-00:00",
                new DayRule(LocalDate.parse("2026-10-19"), "CLOSED", List.of()))).isFalse();
        var night = rules(weekly, List.of());
        assertThat(allows(night, "2026-10-19T17:00:00Z", "00:00-00:00",
                new DayRule(LocalDate.parse("2026-10-19"), "OPEN", List.of("22:00-02:00")))).isTrue();
    }
    @Test void rejectsOverlapIncludingSundayToMondayAndMalformedDefinitions() {
        for (var weeks : List.of(List.of(week(1, "10:00", "11:00", 0), week(1, "10:30", "12:00", 0)),
                List.of(week(7, "22:00", "02:00", 1), week(1, "01:00", "03:00", 0)),
                List.of(week(1, "22:00", "23:00", 1)), List.of(week(1, "10:00", "10:00", 0)),
                List.of(week(8, "10:00", "11:00", 0)), List.of(week(1, "1:00", "11:00", 0)))) {
            assertThatThrownBy(() -> rules(weeks, List.of())).isInstanceOf(BizException.class);
        }
        for (var ex : List.of(new ExceptionInput("2026-02-30", "OPEN", "08:00", "11:00", 0),
                new ExceptionInput("2026-11-01", "CLOSED", null, null, 0),
                new ExceptionInput("2026-10-19", "CLOSED", "08:00", "11:00", 0),
                new ExceptionInput("2026-10-19", "OPEN", null, null, 0))) {
            assertThatThrownBy(() -> rules(List.of(), List.of(ex))).isInstanceOf(BizException.class);
        }
        var closed = new ExceptionInput("2026-10-19", "CLOSED", null, null, 0);
        assertThatThrownBy(() -> rules(List.of(), List.of(closed, closed))).isInstanceOf(BizException.class);
    }
    @Test void canonicalOrderingAdjacentIntervalsEmptyRulesAndStrictInput() {
        var a = week(1, "08:00", "10:00", 0); var b = week(1, "10:00", "12:00", 0);
        assertThat(rules(List.of(b, a), List.of()).weekSchedules()).containsExactly(a, b);
        assertThat(allows(rules(List.of(), List.of()), "2026-10-19T01:00:00Z", "00:00-00:00", null)).isFalse();
        assertThatThrownBy(() -> MenuScheduleDefinitions.normalize(new ReplaceMenuScheduleReq("ALL", 0L,
                List.of(), List.of(), null, null))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> MenuScheduleDefinitions.normalize(new ReplaceMenuScheduleReq("SCHEDULE", 0L,
                null, List.of(), null, null))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> MenuScheduleDefinitions.normalize(new ReplaceMenuScheduleReq("SCHEDULE", 0L,
                List.of(), List.of(), "2026-10-31", "2026-10-01"))).isInstanceOf(BizException.class);
    }
}
