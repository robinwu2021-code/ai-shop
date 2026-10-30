package ai.neargo.shop.beauty.dto;

import java.time.Instant;

/** Safe source workflow projection; observed allocation never grants execution authority. */
public record SourceObservationVO(
        String state,
        long observationEpoch,
        Long sourceRevision,
        String sourceHash,
        Long lastVerifiedRevision,
        String lastVerifiedHash,
        Instant observedAt,
        String reasonCode,
        boolean canStart,
        String admissionKind,
        String manualState,
        Long manualRevision,
        String manualHash) {
    /** Retains the previous public constructor and funded projection defaults. */
    public SourceObservationVO(
            String state,
            long observationEpoch,
            Long sourceRevision,
            String sourceHash,
            Long lastVerifiedRevision,
            String lastVerifiedHash,
            Instant observedAt,
            String reasonCode,
            boolean canStart) {
        this(
                state,
                observationEpoch,
                sourceRevision,
                sourceHash,
                lastVerifiedRevision,
                lastVerifiedHash,
                observedAt,
                reasonCode,
                canStart,
                null,
                null,
                null,
                null);
    }
}
