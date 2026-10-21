package ai.neargo.shop.spi.resource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Bounded owner-owned candidate enumeration and advisory capacity; admission remains the booking owner. */
public interface ResourceCandidatesPort {
    List<ResourceFactsPort.ResourceFact> candidates(Query query);
    List<Capacity> capacity(CapacityQuery query);
    record Query(ResourceBookingPort.Scope scope, boolean publicOnly, Instant at, int limit) {
        public Query {
            if (scope == null || at == null || limit < 1 || limit > 50) {
                throw new IllegalArgumentException("bounded candidate scope required");
            }
        }
    }
    record CapacityQuery(ResourceBookingPort.Scope scope, Set<String> resourceNos, Instant from, Instant to) {
        public CapacityQuery {
            if (scope == null || resourceNos == null || resourceNos.isEmpty() || resourceNos.size() > 50
                    || from == null || to == null || !to.isAfter(from)
                    || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
                throw new IllegalArgumentException("bounded capacity scope required");
            }
            resourceNos = Set.copyOf(resourceNos);
        }
    }
    /** Expired unreleased protected holds remain used; no lease deadline filter is permitted. */
    record Capacity(String resourceNo, Instant from, Instant to, int capacity, int used) { }
}
