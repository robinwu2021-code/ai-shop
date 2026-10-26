package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.OpenHours;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Business-day clock masks only narrow shared hours; no scheduler or policy invalidation is
 * introduced.
 */
public final class ServiceCalendarPolicy {
    private static final Pattern INTERVAL =
            Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d-([01]\\d|2[0-3]):[0-5]\\d");

    private ServiceCalendarPolicy() {}

    /**
     * The caller loads the exception for the computed business day; no exception means shared hours
     * apply.
     */
    public record DayRule(LocalDate date, String mode, List<String> intervals) {}

    /** Uses the same configured local cutover as dining business-day reporting. */
    public static LocalDate businessDate(Instant at, StoreOperationPolicyResultVO policy) {
        return businessDate(at, policy.timezone(), policy.cutoverMinute());
    }

    /** Calendar readers need only original clock fields, without fabricating a payment policy. */
    public static LocalDate businessDate(Instant at, String timezone, int cutoverMinute) {
        var local = at.atZone(ZoneId.of(timezone));
        int minute = local.getHour() * 60 + local.getMinute();
        return minute < cutoverMinute ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }

    /**
     * Ordering and duplicate intervals do not change the command's meaning or its idempotency
     * digest.
     */
    public static List<String> normalize(String mode, List<String> intervals) {
        if (!("OPEN".equals(mode) || "CLOSED".equals(mode))
                || intervals == null
                || intervals.size() > 32
                || "OPEN".equals(mode) && intervals.isEmpty()
                || "CLOSED".equals(mode) && !intervals.isEmpty()
                || intervals.stream()
                        .anyMatch(s -> s == null || !INTERVAL.matcher(s.trim()).matches())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return intervals.stream().map(String::trim).distinct().sorted().toList();
    }

    /**
     * Actual store status, existing hours and the business-day exception must all allow the given
     * instant.
     */
    public static boolean acceptsAt(
            String status,
            String hours,
            StoreOperationPolicyResultVO policy,
            Instant at,
            DayRule rule) {
        if (policy == null) return false;
        return acceptsAt(status, hours, policy.timezone(), policy.cutoverMinute(), at, rule);
    }

    /** Same original decision using only calendar-relevant clock fields. */
    public static boolean acceptsAt(
            String status,
            String hours,
            String timezone,
            int cutoverMinute,
            Instant at,
            DayRule rule) {
        if (!"ACTIVE".equals(status) || at == null) return false;
        try {
            var time = at.atZone(ZoneId.of(timezone)).toLocalTime();
            if (!Boolean.TRUE.equals(OpenHours.openAt(hours, time))) return false;
            if (rule == null) return true;
            return businessDate(at, timezone, cutoverMinute).equals(rule.date())
                    && "OPEN".equals(rule.mode())
                    && normalize(rule.mode(), rule.intervals()).stream()
                            .anyMatch(
                                    interval ->
                                            Boolean.TRUE.equals(OpenHours.openAt(interval, time)));
        } catch (RuntimeException invalidConfiguration) {
            return false;
        }
    }
}
