package ai.neargo.commerce.core.spi;

/** Immutable zero-value cut evidence; original economics and used history remain intact. */
public record SourceRevocation(
        String operationNo,
        long effectiveAt,
        long usedDuration,
        long originalDuration,
        long cashBasis,
        long creditBasis,
        String operatorNo,
        String reason) {}
