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
        try { return acceptsMenuAt(prepare(rules), policy.timezone(), at); }
        catch (RuntimeException invalid) { return false; }
    }

    /** Preparation validates once; compilers must not repeatedly normalize the same original rule at every boundary. */
    public static Prepared prepare(MenuScheduleVO rules) {
        return new Prepared(MenuScheduleDefinitions.normalize(rules));
    }

    /** The original store policy timezone remains authoritative even when the menu carries its own timezone field. */
    public static boolean acceptsMenuAt(Prepared prepared, String timezone, Instant at) {
        var local = at.atZone(ZoneId.of(timezone)).toLocalDateTime();
        return anchored(prepared.rules(), local.toLocalDate(), local)
                || anchored(prepared.rules(), local.toLocalDate().minusDays(1), local);
    }

    /** Only the owning prepare entry can create the validated rule handle. */
    public static final class Prepared {
        private final MenuScheduleVO rules;
        private Prepared(MenuScheduleVO rules) { this.rules = rules; }
        /** Boundary discovery reads the exact normalized rules used by the original decision. */
        public MenuScheduleVO rules() { return rules; }
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
