package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.State;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Exact clock-rule boundaries reuse owner predicates; sampling and default twenty-four-hour
 * schedules are forbidden.
 */
public final class StoreBusinessBoundaries {
    private StoreBusinessBoundaries() {}

    public static Instant nextChange(
            Instant now,
            List<ZoneId> zones,
            List<LocalTime> times,
            List<Instant> policyChanges,
            Function<Instant, State> owner) {
        if (now == null || zones == null || zones.isEmpty() || times == null || owner == null)
            throw new IllegalArgumentException("Original clock rules required");
        Instant end = now.plus(8, ChronoUnit.DAYS);
        var points = new TreeSet<Instant>();
        policyChanges.forEach(at -> add(points, at, now, end));
        for (var zone : zones) {
            var first = now.atZone(zone).toLocalDate().minusDays(2);
            var last = end.atZone(zone).toLocalDate().plusDays(2);
            for (var day = first; !day.isAfter(last); day = day.plusDays(1))
                for (var time : times) {
                    LocalDateTime local = day.atTime(time);
                    var offsets = zone.getRules().getValidOffsets(local);
                    for (var offset : offsets) add(points, local.toInstant(offset), now, end);
                    if (offsets.isEmpty()) {
                        var gap = zone.getRules().getTransition(local);
                        if (gap != null) add(points, gap.getInstant(), now, end);
                    }
                }
            var transition = zone.getRules().nextTransition(now.minusNanos(1));
            while (transition != null && transition.getInstant().isBefore(end)) {
                add(points, transition.getInstant(), now, end);
                transition = zone.getRules().nextTransition(transition.getInstant());
            }
        }
        State current = owner.apply(now);
        for (var point : points) if (owner.apply(point) != current) return point;
        return null;
    }

    private static void add(TreeSet<Instant> points, Instant point, Instant from, Instant until) {
        if (point != null && point.isAfter(from) && !point.isAfter(until)) points.add(point);
        if (points.size() > 200000)
            throw new IllegalStateException("Original business boundary budget exhausted");
    }
}
