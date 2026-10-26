package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.dto.RecipePublicationSnapshot;
import ai.neargo.shop.spi.inventory.InventoryProductionPort.Line;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

/**
 * Base-unit demand from a validated frozen recipe.
 *
 * <p>gross = net * started / (yieldQty * (1 - lossRate)). Terminating results stay exact; a
 * non-terminating quotient (e.g. lossRate 0.1 with yield 3) is rounded once, UP, at the inventory
 * exact-quantity scale ({@link #INVENTORY_QTY_SCALE}, inventory DECIMAL(24,6) /
 * InventoryDemandPlanFacts.quantity), per the enhancement plan rule "由库存规定精度与舍入". Rounding
 * up means stock is never under-consumed, and no order or production event for a published recipe
 * fails on division. Order demand plans and production consumption share this one policy, so both
 * sides compute identical quantities.
 */
public final class ProductionDemandPolicy {
    /** Inventory exact-quantity scale; quantities beyond it cannot be reserved or issued. */
    public static final int INVENTORY_QTY_SCALE = 6;

    private ProductionDemandPolicy() {}

    public static List<Line> evaluate(
            RecipePublicationSnapshot body, OrderLineInput selection, BigDecimal started) {
        if (selection == null
                || selection.bundleChoices() != null && !selection.bundleChoices().isEmpty())
            throw unsupported();
        return evaluateComponent(body, selection, started);
    }

    /**
     * A selected component already owns its quantity; sibling bundle branches are evaluated
     * separately.
     */
    public static List<Line> evaluateComponent(
            RecipePublicationSnapshot body, OrderLineInput selection, BigDecimal started) {
        if (body == null
                || selection == null
                || started == null
                || started.signum() <= 0
                || !selection.skuNo().equals(body.recipe().skuNo())) throw unsupported();
        var selected = new HashSet<String>();
        if (selection.selections() != null)
            for (var choice : selection.selections()) {
                // Saleable modifier/component inventory has a separate demand branch; do not double
                // count it here.
                if (!"SPEC_VALUE".equals(choice.sourceKind())
                        || choice.qty() == null
                        || choice.qty().compareTo(BigDecimal.ONE) != 0) throw unsupported();
                var groups =
                        body.optionGroups().stream()
                                .filter(g -> g.publication().configNo().equals(choice.groupNo()))
                                .toList();
                // A published modifier with no recipe adjustment changes taste only.
                // Cart/menu authority has already validated that selection.
                if (groups.isEmpty()) continue;
                if (groups.size() != 1
                        || choice.configVersion() == null
                        || choice.configVersion()
                                != groups.getFirst().definition().optionGroup().revision())
                    throw unsupported();
                var options =
                        groups.getFirst().definition().optionGroup().data().items().stream()
                                .filter(
                                        o ->
                                                "SPEC_VALUE".equals(o.sourceKind())
                                                        && choice.sourceNo()
                                                                .equals(o.specValueNo()))
                                .toList();
                if (options.size() != 1 || !selected.add(options.getFirst().optionItemNo()))
                    throw unsupported();
            }
        var quantities = new TreeMap<String, BigDecimal>();
        var units = new TreeMap<String, String>();
        var hashes = new TreeMap<String, String>();
        for (var line : body.recipe().lines()) {
            var changes =
                    body.recipe().adjustments().stream()
                            .filter(
                                    a ->
                                            selected.contains(a.optionItemNo())
                                                    && line.lineNo().equals(a.targetLineNo()))
                            .toList();
            long removes = changes.stream().filter(a -> "REMOVE".equals(a.action())).count();
            var sets = changes.stream().filter(a -> "SET".equals(a.action())).toList();
            if (removes > 0 && changes.size() != 1 || sets.size() > 1) throw conflict();
            BigDecimal net =
                    removes > 0
                            ? BigDecimal.ZERO
                            : sets.isEmpty() ? line.qty() : sets.getFirst().qty();
            for (var change : changes)
                if ("ADD".equals(change.action())) net = net.add(change.qty());
            if (net.signum() < 0) throw conflict();
            BigDecimal gross =
                    gross(net.multiply(started), body.recipe().yieldQty(), line.lossRate());
            if (gross.signum() == 0) continue;
            if (units.containsKey(line.itemRef())
                    && (!units.get(line.itemRef()).equals(line.unit())
                            || !hashes.get(line.itemRef()).equals(line.materialDefinitionHash())))
                throw conflict();
            units.put(line.itemRef(), line.unit());
            hashes.put(line.itemRef(), line.materialDefinitionHash());
            quantities.merge(line.itemRef(), gross, BigDecimal::add);
        }
        if (quantities.isEmpty() || quantities.size() > 200) throw unsupported();
        var result = new ArrayList<Line>();
        quantities.forEach(
                (item, quantity) -> {
                    try {
                        if (item.length() > 32
                                || quantity.signum() <= 0
                                || quantity.stripTrailingZeros().scale() > 6
                                || quantity.precision() > 24) throw unsupported();
                    } catch (ArithmeticException precision) {
                        throw unsupported();
                    }
                    result.add(
                            new Line(
                                    item,
                                    quantity.stripTrailingZeros(),
                                    units.get(item),
                                    hashes.get(item)));
                });
        return List.copyOf(result);
    }

    /**
     * One division, so the result is rounded at most once. A quotient that is exact within the
     * inventory scale is returned unchanged; anything finer (non-terminating, or terminating beyond
     * six decimals) is rounded UP.
     */
    static BigDecimal gross(BigDecimal netTotal, BigDecimal yieldQty, BigDecimal lossRate) {
        var divisor = yieldQty.multiply(BigDecimal.ONE.subtract(lossRate));
        return netTotal.divide(divisor, INVENTORY_QTY_SCALE, RoundingMode.UP).stripTrailingZeros();
    }

    private static BizException unsupported() {
        return BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }
}
