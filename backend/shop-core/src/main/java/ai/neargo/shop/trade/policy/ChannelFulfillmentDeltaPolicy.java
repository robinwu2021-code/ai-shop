package ai.neargo.shop.trade.policy;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An original action delta is proven from its locked before-progress, never reconstructed from a
 * later cumulative receipt.
 */
public final class ChannelFulfillmentDeltaPolicy {
    private ChannelFulfillmentDeltaPolicy() {}

    public static BigDecimal delta(
            BigDecimal before,
            BigDecimal after,
            BigDecimal original,
            String unit,
            String originalUnit) {
        if (before == null
                || after == null
                || original == null
                || before.signum() < 0
                || original.signum() <= 0
                || before.stripTrailingZeros().scale() > 6
                || after.stripTrailingZeros().scale() > 6
                || original.stripTrailingZeros().scale() > 6
                || unit == null
                || !Objects.equals(unit, originalUnit)
                || after.compareTo(before) < 0
                || after.compareTo(original) > 0)
            throw new IllegalStateException("Unknown or inconsistent original physical delta");
        return after.subtract(before);
    }
}
