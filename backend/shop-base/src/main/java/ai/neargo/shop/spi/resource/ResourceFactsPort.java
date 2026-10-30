package ai.neargo.shop.spi.resource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/** Canonical owner facts shared by industries; these facts grant neither availability nor booking permission. */
public interface ResourceFactsPort {
    /** Bounded trusted scope; missing/inactive resources are omitted, provider failures must propagate. */
    List<ResourceFact> getBatch(Query query);

    /** Resource identities are explicit; this is not an unscoped resource directory or candidate search. */
    record Query(String tenantNo, String entityNo, String storeNo, Set<String> resourceNos, Instant at) {
        public Query {
            if (tenantNo == null || tenantNo.isBlank() || entityNo == null || entityNo.isBlank()
                    || storeNo == null || storeNo.isBlank() || at == null || resourceNos == null
                    || resourceNos.isEmpty() || resourceNos.size() > 200
                    || resourceNos.stream().anyMatch(no -> no == null || no.isBlank())) {
                throw new IllegalArgumentException("explicit scope, time and 1..200 resource IDs required");
            }
            resourceNos = Set.copyOf(resourceNos);
            at = at.truncatedTo(ChronoUnit.MILLIS);
        }
    }

    /** STAFF is entity-wide and its storeNo stays null; physical resources belong to their canonical store. */
    record ResourceFact(String resourceNo, Type resourceType, String storeNo, String personnelNo,
            String name, int capacity, boolean openOccupied, Long actualUsed) {
        public ResourceFact {
            if (actualUsed == null || actualUsed < 0) {
                throw new IllegalArgumentException("actual capacity observation is required");
            }
        }
        /** Legacy scalar remains exclusively blocking; actual concurrent use preserves residual capacity. */
        public ResourceFact(String resourceNo, Type resourceType, String storeNo, String personnelNo,
                String name, int capacity, boolean openOccupied) {
            this(resourceNo, resourceType, storeNo, personnelNo, name, capacity, openOccupied, 0L);
        }
    }

    /** Stable shared categories; industry names and workflows are not separate resource identities. */
    enum Type {
        STAFF, ROOM, SEAT, DEVICE
    }
}
