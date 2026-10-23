package ai.neargo.shop.common.pricing;

import java.util.Currency;
import java.util.Objects;

/**
 * Exact minor-unit money shared by industry pricing kernels.
 *
 * <p>Signed intermediate differences are valid. Receivable non-negativity and JSON number safety
 * are explicit boundaries, so neither silently narrows checked long arithmetic.
 */
public record MoneyMinor(String currency, long minor) {
    public static final long MAX_JSON_SAFE_MINOR = 9_007_199_254_740_991L;

    public MoneyMinor {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("An explicit canonical ISO currency is required");
        }
        Currency.getInstance(currency);
    }

    /** Adds only matching currencies and fails rather than wrapping a monetary overflow. */
    public MoneyMinor add(MoneyMinor other) {
        requireSameCurrency(other);
        return new MoneyMinor(currency, Math.addExact(minor, other.minor));
    }

    /** Keeps signed differences available for corrections without authorizing a negative receivable. */
    public MoneyMinor subtract(MoneyMinor other) {
        requireSameCurrency(other);
        return new MoneyMinor(currency, Math.subtractExact(minor, other.minor));
    }

    /** Validates numeric transport safety without changing the global JSON serializer. */
    public MoneyMinor requireJsonSafe() {
        if (minor < -MAX_JSON_SAFE_MINOR || minor > MAX_JSON_SAFE_MINOR) {
            throw new IllegalArgumentException("Minor amount exceeds the exact JSON integer range");
        }
        return this;
    }

    /** Applies only at boundaries that represent prices, discounts, or payable amounts. */
    public MoneyMinor requireNonNegative() {
        if (minor < 0) {
            throw new IllegalArgumentException("A non-negative minor amount is required");
        }
        return this;
    }

    private void requireSameCurrency(MoneyMinor other) {
        Objects.requireNonNull(other, "Money is required");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Money currencies must match");
        }
    }
}
