package ai.neargo.shop.spi.points;

import java.util.Optional;

/** Server-owned acceptance terms, read before holding a source admission lock. */
public interface PointsOrderTermsPort {
    Optional<Terms> resolve(
            String market,
            String userNo,
            String merchantNo,
            String storeNo,
            String scene,
            String client);

    record Terms(
            String typeCode,
            String currency,
            long ratePoints,
            long rateMinor,
            boolean earningAllowed,
            boolean redemptionAllowed,
            PointsEarnPort.EarnRule fallback,
            long fallbackRevision,
            String agreementSnapshot) {}
}
