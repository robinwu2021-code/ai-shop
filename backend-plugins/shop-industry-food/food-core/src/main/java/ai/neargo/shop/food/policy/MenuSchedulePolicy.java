package ai.neargo.shop.food.policy;

import ai.neargo.shop.food.dto.MenuScheduleVO;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/** Local start-date windows only narrow the existing store clock; they never grant draft sellability. */
public final class MenuSchedulePolicy {
    private MenuSchedulePolicy() { }
    public static boolean acceptsAt(MenuScheduleVO rules, String status, String hours,
            StoreOperationPolicyResultVO policy, Instant at, ServiceCalendarPolicy.DayRule day) {
        if (!ServiceCalendarPolicy.acceptsAt(status, hours, policy, at, day)) return false;
        try {
            var normalized = MenuScheduleDefinitions.normalize(rules);
            var local = at.atZone(ZoneId.of(policy.timezone())).toLocalDateTime();
            return anchored(normalized, local.toLocalDate(), local)
                    || anchored(normalized, local.toLocalDate().minusDays(1), local);
        } catch (RuntimeException invalid) { return false; }
    }
    private static boolean anchored(MenuScheduleVO rules, LocalDate anchor, LocalDateTime at) {
        if (rules.validFrom() != null && anchor.isBefore(LocalDate.parse(rules.validFrom()))
                || rules.validTo() != null && anchor.isAfter(LocalDate.parse(rules.validTo()))) return false;
        var exception = rules.exceptions().stream().filter(r -> anchor.toString().equals(r.localDate())).findFirst();
        if (exception.isPresent()) {
            var rule = exception.get();
            return "OPEN".equals(rule.mode()) && contains(anchor, at, rule.startTime(), rule.endTime(),
                    rule.endDayOffset());
        }
        return rules.weekSchedules().stream().anyMatch(rule -> rule.weekday() == anchor.getDayOfWeek().getValue()
                && contains(anchor, at, rule.startTime(), rule.endTime(), rule.endDayOffset()));
    }
    private static boolean contains(LocalDate anchor, LocalDateTime at, String start, String end, int offset) {
        return !at.isBefore(anchor.atTime(LocalTime.parse(start)))
                && at.isBefore(anchor.plusDays(offset).atTime(LocalTime.parse(end)));
    }
}
