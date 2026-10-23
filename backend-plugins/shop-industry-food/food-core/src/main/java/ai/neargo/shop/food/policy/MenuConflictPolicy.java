package ai.neargo.shop.food.policy;

import ai.neargo.shop.food.dto.ExceptionInput;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot.Captured;
import ai.neargo.shop.food.dto.MenuScheduleVO;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/** Exact source-date overlap within a revision's effective interval; it never grants menu availability. */
public final class MenuConflictPolicy {
    private static final LocalDate MIN = LocalDate.of(1000, 1, 1);
    private static final LocalDate MAX = LocalDate.of(9999, 12, 31);
    private static final long MAX_OFFSET_SECONDS = 18 * 60 * 60;

    private MenuConflictPolicy() { }

    /** Only equal-priority exclusive menus sharing a sales context can conflict. The upper bound is exclusive. */
    public static boolean conflicts(MenuConfigResultVO left, MenuConfigResultVO right,
            Instant fromInclusive, Instant toExclusive) {
        return conflicts(left, right, fromInclusive, toExclusive,
                (one, two) -> Objects.equals(one.channelBindingNo(), two.channelBindingNo()));
    }

    /** Channel binding numbers belong to individual menus; only their frozen authority identities define overlap. */
    public static boolean conflicts(Captured left, Captured right, Instant fromInclusive, Instant toExclusive) {
        Objects.requireNonNull(left, "Left frozen menu is required");
        Objects.requireNonNull(right, "Right frozen menu is required");
        if (!Objects.equals(left.entityNo(), right.entityNo()) || !Objects.equals(left.storeNo(), right.storeNo())) {
            return false;
        }
        return conflicts(left.definition(), right.definition(), fromInclusive, toExclusive,
                (one, two) -> !"CHANNEL".equals(one.orderSource())
                        || channel(left, one.channelBindingNo()).equals(channel(right, two.channelBindingNo())));
    }

    private record ChannelIdentity(String entity, String store, String provider, String account,
            String externalStore) { }

    private static ChannelIdentity channel(Captured menu, String number) {
        var binding = menu.channelBindings().stream().filter(b -> number.equals(b.bindingNo())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing frozen channel identity"));
        if (!menu.definition().config().menuNo().equals(binding.menuNo())
                || !menu.storeNo().equals(binding.storeNo()) || !"ACTIVE".equals(binding.status())) {
            throw new IllegalArgumentException("Invalid frozen channel scope");
        }
        return new ChannelIdentity(menu.entityNo(), menu.storeNo(), binding.provider(),
                binding.channelAccountRef(), binding.externalStoreRef());
    }

    private static boolean conflicts(MenuConfigResultVO left, MenuConfigResultVO right,
            Instant fromInclusive, Instant toExclusive, BiPredicate<MenuContextInput, MenuContextInput> sameScope) {
        Objects.requireNonNull(fromInclusive, "Effective interval start is required");
        Objects.requireNonNull(left, "Left menu is required");
        Objects.requireNonNull(right, "Right menu is required");
        var a = Objects.requireNonNull(left.config(), "Left menu identity is required");
        var b = Objects.requireNonNull(right.config(), "Right menu identity is required");
        if (a.exclusiveGroup() == null || !a.exclusiveGroup().equals(b.exclusiveGroup())
                || !Objects.equals(a.priority(), b.priority()) || !Objects.equals(a.storeNo(), b.storeNo())
                || !Objects.equals(a.timezone(), b.timezone())) return false;
        var scopes = MenuContextDefinitions.normalize(left.contexts());
        var otherScopes = MenuContextDefinitions.normalize(right.contexts());
        if (scopes.stream().noneMatch(one -> otherScopes.stream().anyMatch(two ->
                one.orderSource().equals(two.orderSource()) && one.fulfillment().equals(two.fulfillment())
                        && sameScope.test(one, two)))) return false;
        var zone = ZoneId.of(a.timezone());
        var first = new Calendar(MenuScheduleDefinitions.normalize(left.schedule()));
        var second = new Calendar(MenuScheduleDefinitions.normalize(right.schedule()));
        Instant earliest = MIN.atStartOfDay().toInstant(ZoneOffset.UTC).minusSeconds(MAX_OFFSET_SECONDS);
        Instant latest = MAX.plusDays(2).atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(MAX_OFFSET_SECONDS);
        Instant from = later(fromInclusive, earliest);
        Instant to = toExclusive == null ? latest : earlier(toExclusive, latest);
        if (!from.isBefore(to)) return false;
        LocalDate low = maximum(MIN, from.atZone(zone).toLocalDate().minusDays(1));
        LocalDate high = minimum(MAX, to.atZone(zone).toLocalDate());
        if (low.isAfter(high)) return false;

        // Exceptions are finite and may be arbitrarily far in the future. Check every affected anchor.
        var special = new TreeSet<LocalDate>();
        first.exceptions.keySet().forEach(date -> neighbors(special, date));
        second.exceptions.keySet().forEach(date -> neighbors(special, date));
        for (LocalDate date : special.subSet(low, true, high, true)) {
            for (int shift = -1; shift <= 1; shift++) {
                if (overlap(first.windows(date), second.windows(date.plusDays(shift)), zone, from, to)) return true;
            }
        }
        // Away from exceptions, local overlap is weekly. Disjoint wall-clock sets cannot share an instant.
        for (int shift = -1; shift <= 1; shift++) {
            LocalDate start = maximum(low, first.from, second.from.minusDays(shift));
            LocalDate end = minimum(high, first.to, second.to.minusDays(shift));
            if (start.isAfter(end)) continue;
            for (int weekday = 1; weekday <= 7; weekday++) {
                LocalDate date = start.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(weekday)));
                if (date.isAfter(end) || !localOverlap(first.weekly(date), second.weekly(date.plusDays(shift)))) {
                    continue;
                }
                // A clipped first week or a DST gap may remove the first occurrence. Never impose a horizon.
                while (!date.isAfter(end)) {
                    LocalDate other = date.plusDays(shift);
                    if (!first.exceptions.containsKey(date) && !second.exceptions.containsKey(other)
                            && overlap(first.weekly(date), second.weekly(other), zone, from, to)) return true;
                    date = date.plusWeeks(1);
                }
            }
        }
        return false;
    }

    private record LocalWindow(LocalDateTime start, LocalDateTime end) { }
    private record Window(Instant start, Instant end) { }

    private static final class Calendar {
        private final MenuScheduleVO schedule;
        private final LocalDate from;
        private final LocalDate to;
        private final Map<LocalDate, ExceptionInput> exceptions;
        Calendar(MenuScheduleVO schedule) {
            this.schedule = schedule;
            this.from = schedule.validFrom() == null ? MIN : LocalDate.parse(schedule.validFrom());
            this.to = schedule.validTo() == null ? MAX : LocalDate.parse(schedule.validTo());
            this.exceptions = schedule.exceptions().stream().collect(Collectors.toMap(
                    e -> LocalDate.parse(e.localDate()), Function.identity()));
        }
        List<LocalWindow> windows(LocalDate date) {
            if (date.isBefore(from) || date.isAfter(to)) return List.of();
            var exception = exceptions.get(date);
            if (exception == null) return weekly(date);
            return "CLOSED".equals(exception.mode()) ? List.of() : List.of(window(date, exception.startTime(),
                    exception.endTime(), exception.endDayOffset()));
        }
        List<LocalWindow> weekly(LocalDate date) {
            return schedule.weekSchedules().stream().filter(rule -> rule.weekday() == date.getDayOfWeek().getValue())
                    .map(rule -> window(date, rule.startTime(), rule.endTime(), rule.endDayOffset())).toList();
        }
    }

    private static LocalWindow window(LocalDate date, String start, String end, int offset) {
        return new LocalWindow(date.atTime(LocalTime.parse(start)), date.plusDays(offset).atTime(LocalTime.parse(end)));
    }

    private static boolean localOverlap(List<LocalWindow> left, List<LocalWindow> right) {
        return left.stream().anyMatch(a -> right.stream().anyMatch(b ->
                a.start.isBefore(b.end) && b.start.isBefore(a.end)));
    }

    private static boolean overlap(List<LocalWindow> left, List<LocalWindow> right, ZoneId zone,
            Instant from, Instant to) {
        if (!localOverlap(left, right)) return false;
        var a = left.stream().flatMap(w -> instants(w, zone).stream()).toList();
        var b = right.stream().flatMap(w -> instants(w, zone).stream()).toList();
        for (var one : a) for (var two : b) {
            Instant start = later(from, later(one.start, two.start));
            Instant end = earlier(to, earlier(one.end, two.end));
            if (start.isBefore(end)) return true;
        }
        return false;
    }

    /** Constant-offset segments preserve both repeated-hour occurrences and remove nonexistent gap times. */
    private static List<Window> instants(LocalWindow local, ZoneId zone) {
        Instant cursor = local.start.toInstant(ZoneOffset.UTC).minusSeconds(MAX_OFFSET_SECONDS);
        Instant limit = local.end.toInstant(ZoneOffset.UTC).plusSeconds(MAX_OFFSET_SECONDS);
        var rules = zone.getRules();
        var result = new ArrayList<Window>();
        while (cursor.isBefore(limit)) {
            var transition = rules.nextTransition(cursor);
            Instant end = transition == null ? limit : earlier(limit, transition.getInstant());
            var offset = rules.getOffset(cursor);
            Instant start = later(cursor, local.start.toInstant(offset));
            Instant finish = earlier(end, local.end.toInstant(offset));
            if (start.isBefore(finish)) result.add(new Window(start, finish));
            cursor = end;
        }
        return result;
    }

    private static void neighbors(Set<LocalDate> dates, LocalDate date) {
        dates.add(date.minusDays(1)); dates.add(date); dates.add(date.plusDays(1));
    }
    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static Instant later(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
    private static LocalDate maximum(LocalDate first, LocalDate... rest) {
        for (var date : rest) if (date.isAfter(first)) first = date;
        return first;
    }
    private static LocalDate minimum(LocalDate first, LocalDate... rest) {
        for (var date : rest) if (date.isBefore(first)) first = date;
        return first;
    }
}
