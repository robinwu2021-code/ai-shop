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
        boolean canStart) {}
