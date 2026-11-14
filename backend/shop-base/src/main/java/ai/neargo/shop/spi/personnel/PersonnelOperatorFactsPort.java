package ai.neargo.shop.spi.personnel;

import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

/**
 * Current authorized account linkage is an observation, never a durable start permission or
 * personnel directory.
 */
public interface PersonnelOperatorFactsPort {
    Observation current(Query query);

    /** Only reviewed service actions may request the authenticated operator's own linkage. */
    record Query(MerchantOperatorAccessPort.Query access) {
        public Query {
            Objects.requireNonNull(access, "access");
            if (!Set.of(
                            "biz:service:task:self",
                            "biz:service:task:view",
                            "biz:service:task:coordinate",
                            "biz:service:task:approve",
                            "biz:service:task:close")
                    .contains(access.permission())) {
                throw new IllegalArgumentException("exact service action permission required");
            }
        }
    }

    enum LinkState {
        LINKED,
        UNLINKED
    }

    /**
     * Null end means indefinite; required wire-property presence is enforced by the bound envelope
     * decoder.
     */
    record OperatorPersonnelFact(
            String personnelNo,
            String assignmentNo,
            long personnelRevision,
            long assignmentRevision,
            long linkRevision,
            Instant validFrom,
            Instant validTo) {
        public OperatorPersonnelFact {
            reference(personnelNo);
            reference(assignmentNo);
            millis(validFrom);
            if (personnelRevision < 0 || assignmentRevision < 0 || linkRevision < 0) {
                throw new IllegalArgumentException("nonnegative owner revisions required");
            }
            if (validTo != null) {
                millis(validTo);
                if (!validTo.isAfter(validFrom)) {
                    throw new IllegalArgumentException("invalid personnel interval");
                }
            }
        }
    }

    /**
     * Authorized absence is distinct from denial, transport failure and an inconsistent owner
     * snapshot.
     */
    record Observation(LinkState state, Instant observedAt, OperatorPersonnelFact personnel) {
        public Observation {
            Objects.requireNonNull(state, "state");
            millis(observedAt);
            if ((state == LinkState.LINKED) != (personnel != null)) {
                throw new IllegalArgumentException("link state must match personnel presence");
            }
            if (personnel != null
                    && (observedAt.isBefore(personnel.validFrom())
                            || personnel.validTo() != null
                                    && !observedAt.isBefore(personnel.validTo()))) {
                throw new IllegalArgumentException("observation outside personnel interval");
            }
        }
    }

    private static void reference(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 32
                || !value.equals(value.strip())) {
            throw new IllegalArgumentException("canonical personnel reference required");
        }
    }

    private static void millis(Instant value) {
        if (value == null || !value.equals(value.truncatedTo(ChronoUnit.MILLIS))) {
            throw new IllegalArgumentException("UTC millisecond timestamp required");
        }
    }
}
