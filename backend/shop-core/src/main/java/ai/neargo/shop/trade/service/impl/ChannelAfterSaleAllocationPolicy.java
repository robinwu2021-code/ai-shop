package ai.neargo.shop.trade.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cumulative floor differences conserve the original immutable allocation even across decimal
 * partial returns.
 */
public final class ChannelAfterSaleAllocationPolicy {
    private ChannelAfterSaleAllocationPolicy() {}

    public static BigDecimal quantity(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{1,6})?"))
            throw new IllegalArgumentException("Invalid original physical quantity");
        var result = new BigDecimal(value);
        if (result.signum() <= 0)
            throw new IllegalArgumentException("Positive physical quantity required");
        return result;
    }

    public static long incrementalCeiling(
            long originalMinor,
            BigDecimal originalQty,
            BigDecimal usedQty,
            long usedMinor,
            BigDecimal requestedQty) {
        if (originalMinor < 0
                || originalQty == null
                || originalQty.signum() <= 0
                || usedQty == null
                || usedQty.signum() < 0
                || requestedQty == null
                || requestedQty.signum() <= 0
                || usedMinor < 0) throw new IllegalArgumentException("Invalid original allocation");
        var total = usedQty.add(requestedQty);
        if (total.compareTo(originalQty) > 0)
            throw new IllegalArgumentException("Original physical ceiling exceeded");
        long prior =
                BigDecimal.valueOf(originalMinor)
                        .multiply(usedQty)
                        .divide(originalQty, 0, RoundingMode.FLOOR)
                        .longValueExact();
        if (usedMinor != prior) throw new IllegalStateException("Original cumulative amount drift");
        long next =
                BigDecimal.valueOf(originalMinor)
                        .multiply(total)
                        .divide(originalQty, 0, RoundingMode.FLOOR)
                        .longValueExact();
        return Math.subtractExact(next, prior);
    }
}
