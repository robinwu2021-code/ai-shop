package ai.neargo.shop.food.policy;

import java.math.BigDecimal;

/** One original stock leaf's cumulative issue and release; callers persist both facts atomically. */
public record FoodLocalStockReservationProgress(BigDecimal original, BigDecimal issued, BigDecimal released) {
    public FoodLocalStockReservationProgress {
        original = FoodLocalStockBalance.quantity(original, false);
        issued = FoodLocalStockBalance.quantity(issued, true);
        released = FoodLocalStockBalance.quantity(released, true);
        if (issued.add(released).compareTo(original) > 0) throw invalid();
    }

    public Change issueTo(FoodLocalStockBalance balance, BigDecimal cumulative) {
        var target = FoodLocalStockBalance.quantity(cumulative, true);
        if (balance == null || target.compareTo(issued) < 0 || target.add(released).compareTo(original) > 0)
            throw invalid();
        var delta = target.subtract(issued);
        if (delta.signum() == 0) return new Change(balance, this, BigDecimal.ZERO);
        return new Change(balance.issueReserved(delta),
                new FoodLocalStockReservationProgress(original, target, released), delta);
    }

    public Change releaseTo(FoodLocalStockBalance balance, BigDecimal cumulative) {
        var target = FoodLocalStockBalance.quantity(cumulative, true);
        if (balance == null || target.compareTo(released) < 0 || target.add(issued).compareTo(original) > 0)
            throw invalid();
        var delta = target.subtract(released);
        if (delta.signum() == 0) return new Change(balance, this, BigDecimal.ZERO);
        return new Change(balance.release(delta),
                new FoodLocalStockReservationProgress(original, issued, target), delta);
    }

    public record Change(FoodLocalStockBalance balance, FoodLocalStockReservationProgress progress, BigDecimal delta) { }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid restaurant stock reservation progress");
    }
}
