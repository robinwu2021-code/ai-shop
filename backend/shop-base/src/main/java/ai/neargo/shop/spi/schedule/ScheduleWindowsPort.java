package ai.neargo.shop.spi.schedule;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/** Weekly schedule inputs shared by industries; neither exceptions, capacity nor service eligibility is promised. */
public interface ScheduleWindowsPort {
    /** Trusted scoped query; conflicts and invalid time rules fail explicitly rather than return partial windows. */
    List<Window> getBatch(Query query);

    /** Bound expansion cost and retain two calendar days of SQL/time-zone boundary headroom. */
    record Query(String tenantNo, String entityNo, String storeNo, Set<String> resourceNos,
            Instant startAt, Instant endAt) {
        public Query {
            if (tenantNo == null || tenantNo.isBlank() || entityNo == null || entityNo.isBlank()
                    || storeNo == null || storeNo.isBlank() || resourceNos == null || resourceNos.isEmpty()
                    || resourceNos.size() > 50 || resourceNos.stream().anyMatch(no -> no == null || no.isBlank())
                    || startAt == null || endAt == null) {
                throw new IllegalArgumentException("explicit scope, time and 1..50 resource IDs required");
            }
            startAt = startAt.truncatedTo(ChronoUnit.MILLIS);
            endAt = endAt.truncatedTo(ChronoUnit.MILLIS);
            if (!endAt.isAfter(startAt) || Duration.between(startAt, endAt).compareTo(Duration.ofDays(31)) > 0
                    || startAt.isBefore(Instant.parse("1000-01-03T00:00:00Z"))
                    || endAt.isAfter(Instant.parse("9999-12-29T00:00:00Z"))) {
                throw new IllegalArgumentException(
                        "positive persisted window up to 31 days within SQL date range required");
            }
            resourceNos = Set.copyOf(resourceNos);
        }
    }

    /** Provenance survives same-assignment merging; distinct adjacent assignments remain separate. */
    record Window(String resourceNo, String storeNo, String assignmentNo, Instant startAt, Instant endAt,
            List<String> ruleNos) {
        public Window {
            ruleNos = List.copyOf(ruleNos);
        }
    }
}
