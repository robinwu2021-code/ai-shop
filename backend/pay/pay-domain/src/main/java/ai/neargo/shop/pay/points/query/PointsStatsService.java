package ai.neargo.shop.pay.points.query;

import java.time.LocalDate;
import java.util.Map;
import java.util.HashMap;

/**
 * Daily projections are replaceable metadata; the ledger and immutable decisions remain
 * authoritative.
 */
public interface PointsStatsService {
    Snapshot rebuild(String typeCode, LocalDate day);

    Snapshot read(String typeCode, LocalDate day, String scopeNo);

    int project(String typeCode, int limit);

    default PeriodSnapshot period(String typeCode, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)
                || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 92)
            throw new IllegalArgumentException("POINTS_STATS_PERIOD_INVALID");
        var totals = new HashMap<String, Long>();
        int available = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            Snapshot snapshot = read(typeCode, day, "TOTAL");
            if (snapshot == null) continue;
            available++;
            for (String key : java.util.List.of("granted_points", "redeemed_points", "refund_returnedPoints", "expired_points"))
                totals.merge(key, snapshot.metrics().getOrDefault(key, 0L), Math::addExact);
        }
        int days = Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1);
        return new PeriodSnapshot(typeCode, from, to, totals, available, days - available);
    }

    record PeriodSnapshot(String typeCode, LocalDate from, LocalDate to, Map<String, Long> metrics,
            int availableDays, int missingDays) {
        public PeriodSnapshot { metrics = Map.copyOf(metrics); }
    }

    record Snapshot(
            String typeCode,
            LocalDate day,
            String scopeNo,
            Map<String, Long> metrics,
            Map<String, Long> skipReasons,
            long throughVoucherId,
            long rebuiltAt) {
        public Snapshot {
            metrics = Map.copyOf(metrics);
            skipReasons = Map.copyOf(skipReasons);
        }
    }
}
