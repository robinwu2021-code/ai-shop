package ai.neargo.shop.common.pricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, single-currency source facts admitted to the pure pricing kernel.
 *
 * <p>The snapshot digest is an evidence reference, not authentication. Application adapters must
 * still verify source ownership, scope/version, prices, and provider capabilities before admission.
 * Benefit, funding, and tax decision facts will be added by their owning implementation slices.
 */
public record PricingFacts(
        String currency,
        String sourceSnapshotHash,
        long sourceVersion,
        List<PricingUnit> units) {

    public PricingFacts {
        new MoneyMinor(currency, 0);
        if (sourceSnapshotHash == null || !sourceSnapshotHash.matches("[0-9a-f]{64}") || sourceVersion < 0) {
            throw new IllegalArgumentException("A canonical source snapshot and non-negative version are required");
        }
        if (units == null || units.isEmpty()) {
            throw new IllegalArgumentException("At least one priced source portion is required");
        }
        units = List.copyOf(units);
        total(currency, units).requireJsonSafe();
        requireDisjointPortions(units);
    }

    /** Totals frozen source amounts; this is neither a discounted quote nor a payment result. */
    public MoneyMinor baseTotal() {
        return total(currency, units);
    }

    private static MoneyMinor total(String currency, List<PricingUnit> units) {
        MoneyMinor result = new MoneyMinor(currency, 0);
        for (PricingUnit unit : units) {
            result = result.add(unit.baseAmount());
        }
        return result;
    }

    private static void requireDisjointPortions(List<PricingUnit> units) {
        Map<ComponentIdentity, List<PricingUnit>> byComponent = new HashMap<>();
        for (PricingUnit unit : units) {
            ComponentIdentity key = new ComponentIdentity(unit.sourceLineNo(), unit.componentRef());
            byComponent.computeIfAbsent(key, ignored -> new ArrayList<>()).add(unit);
        }
        for (List<PricingUnit> portions : byComponent.values()) {
            portions.sort(Comparator.comparing(PricingUnit::quantityStart));
            BigDecimal previousEnd = null;
            for (PricingUnit portion : portions) {
                if (previousEnd != null && portion.quantityStart().compareTo(previousEnd) < 0) {
                    throw new IllegalArgumentException("Source component quantity portions must not overlap");
                }
                previousEnd = portion.quantityEnd();
            }
        }
    }

    private record ComponentIdentity(String sourceLineNo, String componentRef) {
    }
}
