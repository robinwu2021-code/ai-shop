package ai.neargo.commerce.core.mall;

import java.math.BigInteger;

/** Exact minor-unit valuation: floor once per original source, never truncate elapsed days. */
public final class UpgradeValue {
    private UpgradeValue() {}

    public static long remaining(long basis, long duration, long used, boolean gift) {
        if (basis < 0 || duration <= 0 || used < 0 || used > duration)
            throw new IllegalArgumentException("invalid source valuation");
        if (gift) return 0;
        return BigInteger.valueOf(basis)
                .multiply(BigInteger.valueOf(duration - used))
                .divide(BigInteger.valueOf(duration))
                .longValueExact();
    }
}
