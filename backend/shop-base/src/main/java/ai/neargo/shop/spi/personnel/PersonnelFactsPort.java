package ai.neargo.shop.spi.personnel;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/** Shared business personnel facts; neither login permissions nor a promise of reservable staff capacity. */
public interface PersonnelFactsPort {
    /** Read outside caller transactions; unknown/expired personnel are omitted and provider failures propagate. */
    List<PersonnelFact> getBatch(Query query);

    /** Explicit trusted scope and bounded IDs avoid an accidental unscoped personnel directory export. */
    record Query(String tenantNo, String entityNo, String storeNo, Set<String> personnelNos, Instant at) {
        public Query {
            if (tenantNo == null || tenantNo.isBlank() || entityNo == null || entityNo.isBlank()
                    || storeNo == null || storeNo.isBlank() || at == null || personnelNos == null
                    || personnelNos.isEmpty() || personnelNos.size() > 200
                    || personnelNos.stream().anyMatch(no -> no == null || no.isBlank())) {
                throw new IllegalArgumentException("explicit scope, time and 1..200 personnel IDs required");
            }
            personnelNos = Set.copyOf(personnelNos);
            at = at.truncatedTo(ChronoUnit.MILLIS);
        }
    }

    /** Contact references, account identities and authorization roles deliberately stay with their owners. */
    record PersonnelFact(String personnelNo, String assignmentNo, String storeNo, String displayName,
            String levelCode, boolean publicVisible, Instant validFrom, Instant validTo,
            List<String> positionCodes, List<Qualification> qualifications) {
        public PersonnelFact {
            positionCodes = List.copyOf(positionCodes);
            qualifications = List.copyOf(qualifications);
        }
    }

    /** Verified effective qualification scope; the caller still validates the intended service and interval. */
    record Qualification(String skillNo, String storeNo, String goodsNo, String skuNo, String levelCode,
            Instant validFrom, Instant validTo) {
    }
}
