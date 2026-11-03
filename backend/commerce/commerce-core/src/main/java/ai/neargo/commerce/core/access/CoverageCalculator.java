package ai.neargo.commerce.core.access;

import ai.neargo.commerce.api.access.AccessStatus;
import ai.neargo.commerce.api.subscription.*;

import java.time.Instant;
import java.util.*;

public final class CoverageCalculator {
    private CoverageCalculator() {}

    public static CoverageView evaluate(List<AccessSegment> rows, Instant at) {
        if (rows.isEmpty())
            throw new IllegalArgumentException("empty schedule requires an explicit code");
        return evaluate(rows.getFirst().entitlementCode(), rows, at);
    }

    public static CoverageView evaluate(String code, List<AccessSegment> rows, Instant at) {
        var merged = intervals(code, rows);
        return at(code, merged, at);
    }

    public static List<CoverageInterval> intervals(String code, List<AccessSegment> rows) {
        var merged = new ArrayList<CoverageInterval>();
        for (var s : rows.stream().sorted(Comparator.comparing(AccessSegment::startsAt)).toList()) {
            if (!code.equals(s.entitlementCode())
                    || s.endsAt() != null && !s.endsAt().isAfter(s.startsAt()))
                throw new IllegalArgumentException("invalid access segment");
            var prev = merged.isEmpty() ? null : merged.getLast();
            if (prev == null || prev.endsAt() != null && prev.endsAt().isBefore(s.startsAt())) {
                merged.add(new CoverageInterval(s.startsAt(), s.endsAt()));
            } else if (prev.endsAt() != null
                    && (s.endsAt() == null || s.endsAt().isAfter(prev.endsAt()))) {
                merged.set(merged.size() - 1, new CoverageInterval(prev.startsAt(), s.endsAt()));
            }
        }
        return List.copyOf(merged);
    }

    public static CoverageView at(String code, List<CoverageInterval> merged, Instant at) {
        CoverageInterval current = null, ended = null;
        Instant next = null;
        for (var interval : merged) {
            if (interval.startsAt().isAfter(at)) {
                next = interval.startsAt();
                break;
            }
            if (interval.endsAt() == null || at.isBefore(interval.endsAt())) current = interval;
            else ended = interval;
        }
        return new CoverageView(
                code,
                current != null && current.endsAt() == null,
                Optional.ofNullable(current),
                Optional.ofNullable(ended),
                Optional.ofNullable(current == null ? next : current.endsAt()),
                Optional.ofNullable(next));
    }

    /** Commercial coverage only. End entry additionally requires host authority and proof. */
    public static AccessStatus status(CoverageView coverage, boolean revoked) {
        if (coverage.currentCoverage().isPresent()) return AccessStatus.ALLOWED;
        if (coverage.nextStartAt().isPresent()) return AccessStatus.NOT_STARTED;
        if (revoked) return AccessStatus.REVOKED;
        if (coverage.mostRecentEndedCoverage().isPresent()) return AccessStatus.EXPIRED;
        return AccessStatus.NOT_PURCHASED;
    }
}
