package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.dto.RecipePublicationSnapshot;
import ai.neargo.shop.spi.inventory.InventoryProductionPort.Line;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

/** Exact base-unit demand from a validated frozen recipe; unsupported precision never rounds. */
public final class ProductionDemandPolicy {
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
            BigDecimal gross;
            try {
                gross =
                        net.multiply(started)
                                .divide(body.recipe().yieldQty())
                                .divide(BigDecimal.ONE.subtract(line.lossRate()))
                                .stripTrailingZeros();
            } catch (ArithmeticException precision) {
                throw unsupported();
            }
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

    private static BizException unsupported() {
        return BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }
}
