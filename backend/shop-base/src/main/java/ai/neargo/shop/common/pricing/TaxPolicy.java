package ai.neargo.shop.common.pricing;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Frozen admitted regional policy; construction does not publish a legal profile or authenticate its owner. */
public record TaxPolicy(String policyNo, int version, String countryCode, String regionCode, String currency,
        Instant effectiveFrom, Instant effectiveTo, PriceTaxMode priceMode, Set<String> allowedContexts,
        Map<TaxClass, Integer> rates, String algorithm, String evidenceRef) {
    public TaxPolicy {
        PricingPortion.requireIdentity(policyNo, 64);
        PricingPortion.requireIdentity(evidenceRef, 64);
        requireCountry(countryCode);
        if (regionCode != null) {
            PricingPortion.requireIdentity(regionCode, 32);
        }
        new MoneyMinor(currency, 0);
        Objects.requireNonNull(effectiveFrom);
        Objects.requireNonNull(priceMode);
        PricingPortion.requireIdentity(algorithm, 64);
        if (version < 1 || effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("Positive version and nonempty legal validity interval required");
        }
        allowedContexts = Set.copyOf(allowedContexts);
        if (allowedContexts.isEmpty() || allowedContexts.size() > 32) {
            throw new IllegalArgumentException("Explicit bounded legal contexts required");
        }
        allowedContexts.forEach(value -> PricingPortion.requireIdentity(value, 64));
        var copy = new LinkedHashMap<TaxClass, Integer>();
        rates.forEach((taxClass, rate) -> {
            Objects.requireNonNull(taxClass);
            if (taxClass == TaxClass.UNKNOWN || switch (taxClass) {
                case STANDARD -> rate == null || rate <= 0 || rate > 10000;
                case ZERO_RATED -> rate == null || rate != 0;
                case EXEMPT, OUT_OF_SCOPE -> rate != null;
                case UNKNOWN -> true;
            }) {
                throw new IllegalArgumentException("Tax class and explicit rate semantics must match");
            }
            copy.put(taxClass, rate);
        });
        // Null means non-taxable, deliberately distinct from a taxable zero rate.
        rates = Collections.unmodifiableMap(copy);
    }

    static void requireCountry(String value) {
        if (value == null || !value.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("Explicit canonical country required");
        }
    }
}
