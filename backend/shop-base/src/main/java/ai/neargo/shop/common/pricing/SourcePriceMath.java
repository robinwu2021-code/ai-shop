package ai.neargo.shop.common.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Source-owned quantity pricing, separate from discount selection, tax and asset execution. */
public final class SourcePriceMath {
    private SourcePriceMath() { }

    public static BigDecimal quantity(BigDecimal value) {
        var result = Objects.requireNonNull(value).stripTrailingZeros();
        if (result.signum() <= 0 || result.scale() > 6 || (long) result.precision() - result.scale() > 14) {
            throw new IllegalArgumentException("Positive DECIMAL(20,6) source quantity required");
        }
        return result.scale() < 0 ? result.setScale(0) : result;
    }

    public static MoneyMinor extend(MoneyMinor price, BigDecimal quantity) {
        price.requireNonNegative().requireJsonSafe();
        long amount = BigDecimal.valueOf(price.minor()).multiply(quantity(quantity))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        return new MoneyMinor(price.currency(), amount).requireJsonSafe();
    }

    public static MoneyMinor topup(MoneyMinor selected, MoneyMinor base) {
        selected.requireNonNegative().requireJsonSafe();
        base.requireNonNegative().requireJsonSafe();
        if (!selected.currency().equals(base.currency())) throw new IllegalArgumentException("Currency mismatch");
        return new MoneyMinor(selected.currency(), Math.max(0, selected.minor() - base.minor()));
    }
}
