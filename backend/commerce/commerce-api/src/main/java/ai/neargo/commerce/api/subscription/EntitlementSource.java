package ai.neargo.commerce.api.subscription;

import ai.neargo.commerce.api.common.*;

import java.util.Optional;

/**
 * Original source economics/duration plus explicit retired/frozen duration; boundaries are
 * exclusive.
 */
public record EntitlementSource(
        String sourceNo,
        String entitlementCode,
        Optional<String> planVersionNo,
        String originNo,
        long durationMillis,
        long consumedMillis,
        Money valuationBasis,
        boolean permanent,
        String lifecycle,
        long revokedDurationMillis) {
    public EntitlementSource(
            String sourceNo,
            String entitlementCode,
            Optional<String> planVersionNo,
            String originNo,
            long durationMillis,
            long consumedMillis,
            Money valuationBasis,
            boolean permanent) {
        this(
                sourceNo,
                entitlementCode,
                planVersionNo,
                originNo,
                durationMillis,
                consumedMillis,
                valuationBasis,
                permanent,
                "NORMAL",
                0);
    }
}
