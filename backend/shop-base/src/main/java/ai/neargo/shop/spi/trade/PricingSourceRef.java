package ai.neargo.shop.spi.trade;

/**
 * Versioned source/scope reference for shared pricing requests across industry boundaries.
 *
 * <p>This does not replace entitlement.SourceRef and is never a debit identity or authorization
 * token. A registered owner must resolve it and verify caller access before supplying pricing facts.
 */
public record PricingSourceRef(
        String sourceSystem,
        String sourceType,
        String sourceNo,
        long sourceVersion,
        String scopeNo,
        Long scopeVersion) {

    public PricingSourceRef {
        requireIdentity(sourceSystem);
        requireIdentity(sourceType);
        requireIdentity(sourceNo);
        if (sourceVersion < 0 || (scopeNo == null) != (scopeVersion == null)) {
            throw new IllegalArgumentException("Source versions and paired scope identity must be explicit");
        }
        if (scopeNo != null) {
            requireIdentity(scopeNo);
            if (scopeVersion < 0) {
                throw new IllegalArgumentException("Scope version must be non-negative");
            }
        }
    }

    private static void requireIdentity(String value) {
        if (value == null || value.isBlank() || value.length() > 32
                || value.codePoints().anyMatch(c -> Character.isWhitespace(c)
                        || Character.isSpaceChar(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("A canonical pricing source identity is required");
        }
    }
}
