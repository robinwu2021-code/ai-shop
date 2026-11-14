package ai.neargo.shop.food.policy;

import java.math.BigDecimal;

/** Immutable quantities for one restaurant-owned store/item balance; persistence supplies the lock. */
public record FoodLocalStockBalance(BigDecimal onHand, BigDecimal reserved) {
    private static final BigDecimal MAX = new BigDecimal("999999999999999999.999999");

    public FoodLocalStockBalance {
        onHand = quantity(onHand, true);
        reserved = quantity(reserved, true);
        if (reserved.compareTo(onHand) > 0) throw invalid();
    }

    public BigDecimal available() {
        return onHand.subtract(reserved);
    }

    public FoodLocalStockBalance reserve(BigDecimal amount) {
        return new FoodLocalStockBalance(onHand, reserved.add(quantity(amount, false)));
    }

    public FoodLocalStockBalance release(BigDecimal amount) {
        return new FoodLocalStockBalance(onHand, reserved.subtract(quantity(amount, false)));
    }

    public FoodLocalStockBalance issueReserved(BigDecimal amount) {
        var requested = quantity(amount, false);
        return new FoodLocalStockBalance(onHand.subtract(requested), reserved.subtract(requested));
    }

    public FoodLocalStockBalance adjust(BigDecimal delta) {
        if (delta == null || delta.signum() == 0) throw invalid();
        quantity(delta.abs(), false);
        return new FoodLocalStockBalance(onHand.add(delta), reserved);
    }

    static BigDecimal quantity(BigDecimal value, boolean zeroAllowed) {
        if (value == null || value.signum() < 0 || !zeroAllowed && value.signum() == 0
                || value.stripTrailingZeros().scale() > 6 || value.compareTo(MAX) > 0) throw invalid();
        return value.stripTrailingZeros();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid restaurant stock quantity or balance");
    }
}
