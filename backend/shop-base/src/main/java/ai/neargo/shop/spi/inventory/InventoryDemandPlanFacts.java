package ai.neargo.shop.spi.inventory;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Branch;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Leaf;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Canonical plan identity shared by order owner, inventory owner and remote transports. */
public final class InventoryDemandPlanFacts {
    private InventoryDemandPlanFacts() {}

    public static BigDecimal quantity(BigDecimal value, boolean zero) {
        if (value == null
                || value.signum() < 0
                || !zero && value.signum() == 0
                || value.stripTrailingZeros().scale() > 6
                || value.stripTrailingZeros().precision() - value.stripTrailingZeros().scale() > 18)
            throw new IllegalArgumentException("Exact quantity required");
        return value.stripTrailingZeros();
    }

    public static String hash(Plan plan) {
        return PricingSnapshotHash.of(
                List.of("INVENTORY_DEMAND_PLAN_V1", plan.scope(), plan.planNo(), canonical(plan)));
    }

    public static Plan validate(Plan plan) {
        if (plan == null
                || plan.scope() == null
                || !MaterialCatalogFacts.validKey(plan.planNo(), 32)
                || plan.branches().isEmpty()
                || plan.branches().size() > 256) throw new IllegalArgumentException("Invalid plan");
        var components = new HashSet<String>();
        var leaves = new HashSet<String>();
        for (var b : plan.branches()) {
            if (!MaterialCatalogFacts.validKey(b.sourceComponentRef(), 64)
                    || !components.add(b.sourceComponentRef())
                    || !MaterialCatalogFacts.validKey(b.storeNo(), 32)
                    || !MaterialCatalogFacts.validKey(b.fulfillmentRef(), 64)
                    || !Set.of("FINISHED_SKU", "RECIPE_MATERIAL").contains(b.kind())
                    || !MaterialCatalogFacts.validKey(b.unit(), 16)
                    || b.leaves().isEmpty()
                    || b.leaves().size() > 200
                    || "FINISHED_SKU".equals(b.kind()) && b.leaves().size() != 1)
                throw new IllegalArgumentException("Invalid exclusive branch");
            quantity(b.quantity(), false);
            for (var l : b.leaves()) {
                if (!MaterialCatalogFacts.validKey(l.leafRef(), 64)
                        || !leaves.add(l.leafRef())
                        || !MaterialCatalogFacts.validKey(l.itemRef(), 32)
                        || !MaterialCatalogFacts.validKey(l.unit(), 16)
                        || l.definitionHash() == null
                        || !l.definitionHash().matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException("Invalid leaf");
                quantity(l.quantity(), false);
            }
        }
        if (!hash(plan).equals(plan.planHash()))
            throw new IllegalArgumentException("Plan hash mismatch");
        return plan;
    }

    private static List<Object> canonical(Plan p) {
        var result = new ArrayList<Object>();
        p.branches().stream()
                .sorted(Comparator.comparing(Branch::sourceComponentRef))
                .forEach(
                        b -> {
                            var leaves =
                                    b.leaves().stream()
                                            .sorted(Comparator.comparing(Leaf::leafRef))
                                            .map(
                                                    l ->
                                                            List.of(
                                                                    l.leafRef(),
                                                                    l.itemRef(),
                                                                    quantity(l.quantity(), false)
                                                                            .toPlainString(),
                                                                    l.unit(),
                                                                    l.definitionHash()))
                                            .toList();
                            result.add(
                                    List.of(
                                            b.sourceComponentRef(),
                                            b.fulfillmentRef(),
                                            b.storeNo(),
                                            b.kind(),
                                            quantity(b.quantity(), false).toPlainString(),
                                            b.unit(),
                                            leaves,
                                            b.recipeRevisionNo() == null
                                                    ? ""
                                                    : b.recipeRevisionNo()));
                        });
        return result;
    }
}
