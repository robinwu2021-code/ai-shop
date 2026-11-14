package ai.neargo.commerce.api.common;

/** Trusted, immutable settlement policy. Money.minor remains scale-2, including TWD. */
public record MoneyPolicy(String version, String currency, int scale, long cashQuantum, long allocationQuantum) {
    public MoneyPolicy {
        if (version == null || version.isBlank() || version.length() > 64 || currency == null
                || !currency.matches("[A-Z]{3}") || scale != 2 || cashQuantum < 1
                || cashQuantum != allocationQuantum)
            throw new IllegalArgumentException("INVALID_MONEY_POLICY");
    }
    public static MoneyPolicy legacy(String currency) {
        return new MoneyPolicy("legacy-minor-v1", currency, 2, 1, 1);
    }
    public boolean onGrid(Money value) {
        return value != null && currency.equals(value.currency()) && value.minor() >= 0
                && value.minor() % cashQuantum == 0;
    }
}
