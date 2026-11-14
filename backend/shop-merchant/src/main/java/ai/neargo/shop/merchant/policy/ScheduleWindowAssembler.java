package ai.neargo.shop.merchant.policy;

import ai.neargo.shop.merchant.dto.ScheduleRuleFactRow;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort.Query;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort.Window;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Expands weekly local rules without guessing DST offsets or duplicating shared personnel capacity. */
public final class ScheduleWindowAssembler {
    private ScheduleWindowAssembler() {
    }

    /** Validate the complete related-store snapshot before exposing only the requested canonical store. */
    public static List<Window> expand(List<ScheduleRuleFactRow> rows, Query query) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Window> raw = new ArrayList<>();
        for (var row : rows) {
            ScheduleWindowAssembler.expandRule(row, query, raw);
        }
        raw.sort(Comparator.comparing(Window::resourceNo).thenComparing(Window::startAt)
                .thenComparing(Window::storeNo).thenComparing(Window::assignmentNo,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
        List<Window> merged = new ArrayList<>();
        for (var next : raw) {
            if (merged.isEmpty()) {
                merged.add(next);
                continue;
            }
            var previous = merged.getLast();
            boolean sameResource = previous.resourceNo().equals(next.resourceNo());
            boolean overlap = sameResource && next.startAt().isBefore(previous.endAt());
            boolean sameStore = previous.storeNo().equals(next.storeNo());
            boolean sameAssignment = Objects.equals(previous.assignmentNo(), next.assignmentNo());
            if (overlap && !sameStore) {
                throw new IllegalStateException("cross-store schedule overlap");
            }
            if (overlap && sameStore && !sameAssignment) {
                throw new IllegalStateException("ambiguous schedule employment");
            }
            if (sameResource && sameStore && sameAssignment && !next.startAt().isAfter(previous.endAt())) {
                var provenance = new TreeSet<>(previous.ruleNos());
                provenance.addAll(next.ruleNos());
                merged.set(merged.size() - 1, new Window(previous.resourceNo(), previous.storeNo(),
                        previous.assignmentNo(), previous.startAt(), ScheduleWindowAssembler.max(previous.endAt(),
                                next.endAt()), new ArrayList<>(provenance)));
            } else {
                merged.add(next);
            }
        }
        String canonicalStore = rows.getFirst().requestedStoreNo();
        return merged.stream().filter(window -> window.storeNo().equals(canonicalStore)).toList();
    }

    private static void expandRule(ScheduleRuleFactRow row, Query query, List<Window> output) {
        ZoneId zone;
        try {
            zone = ZoneId.of(row.timezone());
        } catch (DateTimeException ex) {
            throw new IllegalStateException("invalid schedule timezone", ex);
        }
        Instant from = query.startAt();
        Instant until = query.endAt();
        if (row.staff()) {
            from = ScheduleWindowAssembler.max(from, row.employmentFrom().toInstant(ZoneOffset.UTC));
            if (row.employmentTo() != null) {
                until = ScheduleWindowAssembler.min(until, row.employmentTo().toInstant(ZoneOffset.UTC));
            }
        }
        if (!until.isAfter(from)) {
            return;
        }
        // UTC candidate dates also cover skipped local dates: shifts are <=24h and offsets are within +/-18h.
        var first = from.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(2);
        var last = until.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(2);
        for (var day = first; !day.isAfter(last); day = day.plusDays(1)) {
            if (day.isBefore(row.validFrom()) || row.validTo() != null && day.isAfter(row.validTo())
                    || (row.weekdayMask() & (1 << (day.getDayOfWeek().getValue() - 1))) == 0) {
                continue;
            }
            var localStart = day.atStartOfDay().plusMinutes(row.startMinute());
            var localEnd = day.plusDays(row.endDayOffset()).atStartOfDay().plusMinutes(row.endMinute());
            var start = ScheduleWindowAssembler.endpoint(localStart, zone);
            var end = ScheduleWindowAssembler.endpoint(localEnd, zone);
            // Offset ranges only exclude irrelevant occurrences; they never choose a missing/repeated local time.
            if (!end.latest().isAfter(from) || !start.earliest().isBefore(until)) {
                continue;
            }
            if (!start.unique() || !end.unique()) {
                throw new IllegalStateException("DST endpoint is missing or ambiguous");
            }
            Instant clippedStart = ScheduleWindowAssembler.max(start.earliest(), from);
            Instant clippedEnd = ScheduleWindowAssembler.min(end.earliest(), until);
            if (!clippedEnd.isAfter(clippedStart)) {
                continue;
            }
            output.add(new Window(row.resourceNo(), row.storeNo(), row.assignmentNo(), clippedStart, clippedEnd,
                    List.of(row.ruleNo())));
            if (output.size() > 10000) {
                throw new IllegalStateException("too many expanded schedule windows");
            }
        }
    }

    private static Endpoint endpoint(LocalDateTime local, ZoneId zone) {
        var offsets = zone.getRules().getValidOffsets(local);
        boolean unique = offsets.size() == 1;
        if (offsets.isEmpty()) {
            var transition = zone.getRules().getTransition(local);
            offsets = List.of(transition.getOffsetBefore(), transition.getOffsetAfter());
        }
        Instant earliest = local.toInstant(offsets.getFirst());
        Instant latest = earliest;
        for (var offset : offsets) {
            Instant candidate = local.toInstant(offset);
            earliest = ScheduleWindowAssembler.min(earliest, candidate);
            latest = ScheduleWindowAssembler.max(latest, candidate);
        }
        return new Endpoint(earliest, latest, unique);
    }

    private static Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static Instant max(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private record Endpoint(Instant earliest, Instant latest, boolean unique) {
    }
}
