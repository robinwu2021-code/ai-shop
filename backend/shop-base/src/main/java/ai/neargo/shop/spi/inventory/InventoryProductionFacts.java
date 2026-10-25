package ai.neargo.shop.spi.inventory;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.inventory.InventoryProductionPort.Command;
import ai.neargo.shop.spi.inventory.InventoryProductionPort.Line;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Local owner, remote adapter and industry replay hash the same normalized source fact. */
public final class InventoryProductionFacts {
    private InventoryProductionFacts() {}

    public static Command normalize(Command c) {
        if (c == null
                || !MaterialCatalogFacts.validKey(c.entityNo(), 32)
                || !MaterialCatalogFacts.validKey(c.storeNo(), 32)
                || !MaterialCatalogFacts.validKey(c.eventNo(), 32)
                || c.purpose() == null
                || !List.of("PRODUCTION", "REMAKE", "MATERIAL_WASTE", "MATERIAL_SUPPLEMENT")
                        .contains(c.purpose())
                || c.occurredAt() == null
                || c.lines().isEmpty()
                || c.lines().size() > 200) throw new IllegalArgumentException();
        var ids = new HashSet<String>();
        for (Line l : c.lines()) {
            if (l == null
                    || !MaterialCatalogFacts.validKey(l.itemRef(), 32)
                    || !ids.add(l.itemRef())
                    || !MaterialCatalogFacts.validKey(l.unit(), 16)
                    || l.quantity() == null
                    || l.quantity().signum() <= 0
                    || (c.planProof() == null
                            && (l.quantity().precision() > 18 || l.quantity().scale() > 6))
                    || l.definitionHash() == null
                    || !l.definitionHash().matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid original production line");
            }
            if (c.planProof() != null) InventoryDemandPlanFacts.quantity(l.quantity(), false);
        }
        return new Command(
                c.entityNo(),
                c.storeNo(),
                c.eventNo(),
                c.purpose(),
                c.occurredAt(),
                c.lines().stream()
                        .map(
                                l ->
                                        new Line(
                                                l.itemRef(),
                                                l.quantity().stripTrailingZeros(),
                                                l.unit(),
                                                l.definitionHash()))
                        .sorted(Comparator.comparing(Line::itemRef))
                        .toList(),
                c.planProof());
    }

    public static String hash(Command c) {
        return PricingSnapshotHash.of(List.of("FOOD_PRODUCTION_V1", normalize(c)));
    }

    /**
     * Existing inventory documents store integer totals; neither a line nor its sum may silently
     * overflow.
     */
    public static int integerTotal(Command c) {
        int total = 0;
        for (Line l : normalize(c).lines())
            total = Math.addExact(total, l.quantity().intValueExact());
        return total;
    }
}
