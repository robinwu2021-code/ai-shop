package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.dto.CartSelectionInput;
import ai.neargo.shop.food.dto.CatalogDependencyVO;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.dto.SelectionRules;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Frozen menu facts authorize draft choices without pretending to quote or reserve their
 * components.
 */
public final class CartLinePolicy {
    private CartLinePolicy() {}

    /**
     * Requiring the selected publication prevents silently migrating a customer's choices to a new
     * menu.
     */
    public static void validate(OrderLineInput line, CatalogVO catalog, ObjectMapper json) {
        CartInputPolicy.line(line);
        if (catalog == null || catalog.menus() == null || catalog.items() == null) throw conflict();
        var menu =
                catalog.menus().stream()
                        .filter(
                                m ->
                                        line.menuNo().equals(m.menuNo())
                                                && line.menuRevisionNo().equals(m.revisionNo()))
                        .findFirst()
                        .orElseThrow(CartLinePolicy::conflict);
        var item =
                catalog.items().stream()
                        .filter(
                                i ->
                                        line.menuNo().equals(i.menuNo())
                                                && line.menuItemNo().equals(i.menuItemNo())
                                                && line.goodsNo().equals(i.goodsNo()))
                        .findFirst()
                        .orElseThrow(CartLinePolicy::conflict);
        if (item.skus() == null) throw conflict();
        var sku =
                item.skus().stream()
                        .filter(s -> line.skuNo().equals(s.skuNo()))
                        .findFirst()
                        .orElseThrow(CartLinePolicy::conflict);
        if (item.dish() == null
                || item.dish().measurement() == null
                || !DiningMeasurementPolicy.valid(item.dish().measurement())
                || !Boolean.TRUE.equals(sku.onSale())
                || !Boolean.FALSE.equals(sku.restricted())) throw bad();
        DiningMeasurementPolicy.quantity(item.dish().measurement(), line.qty());
        if ("COUNT".equals(item.dish().measurement().mode())
                        && !"BUNDLE".equals(item.dish().dishKind())
                        && sku.recipeNo() == null
                        && sku.available() != null
                        && line.qty().compareTo(BigDecimal.valueOf(sku.available())) > 0
                || sku.remainingQuota() != null && line.qty().compareTo(sku.remainingQuota()) > 0)
            throw bad();
        var dependencies =
                menu.dependencies() == null ? List.<CatalogDependencyVO>of() : menu.dependencies();
        selections(line, item.optionGroupNos(), dependencies, json);
        bundles(line, "BUNDLE".equals(item.dish().dishKind()), dependencies);
    }

    private static void selections(
            OrderLineInput line,
            List<String> bound,
            List<CatalogDependencyVO> dependencies,
            ObjectMapper json) {
        if (bound == null) throw bad();
        var picked = new HashMap<String, List<CartSelectionInput>>();
        for (var selection : line.selections()) {
            if (!bound.contains(selection.groupNo())) throw bad();
            picked.computeIfAbsent(selection.groupNo(), ignored -> new ArrayList<>())
                    .add(selection);
        }
        for (String groupNo : bound) {
            var matches =
                    dependencies.stream()
                            .filter(
                                    d ->
                                            "OPTION_GROUP".equals(d.configKind())
                                                    && groupNo.equals(d.configNo()))
                            .toList();
            if (matches.size() != 1 || matches.getFirst().optionGroup() == null) throw conflict();
            var group = matches.getFirst().optionGroup();
            if (!groupNo.equals(group.configNo()) || group.data() == null) throw conflict();
            validateGroup(group, picked.getOrDefault(groupNo, List.of()), json);
        }
    }

    private static void validateGroup(
            OptionGroupConfigVO group, List<CartSelectionInput> picked, ObjectMapper json) {
        var quantities = new HashMap<String, BigDecimal>();
        for (var selection : picked) {
            if (selection.configVersion() != group.revision()) throw conflict();
            var matches =
                    group.data().items().stream()
                            .filter(
                                    i ->
                                            selection.sourceKind().equals(i.sourceKind())
                                                    && selection
                                                            .sourceNo()
                                                            .equals(
                                                                    "SPEC_VALUE"
                                                                                    .equals(
                                                                                            i
                                                                                                    .sourceKind())
                                                                            ? i.specValueNo()
                                                                            : i.modifierNo()))
                            .toList();
            if (matches.size() != 1
                    || quantities.put(matches.getFirst().optionItemNo(), selection.qty()) != null) {
                throw bad();
            }
        }
        var total = BigDecimal.ZERO;
        for (var item : group.data().items()) {
            var qty = quantities.getOrDefault(item.optionItemNo(), BigDecimal.ZERO);
            bounds(qty, item.minQty(), item.maxQty());
            total = total.add(qty);
        }
        bounds(total, group.data().minQty(), group.data().maxQty());
        SelectionRules rules;
        try {
            rules = json.convertValue(group.data().selectionRules(), SelectionRules.class);
        } catch (IllegalArgumentException ex) {
            throw bad();
        }
        if (rules == null || rules.mutexSets() == null || rules.requires() == null) throw bad();
        Set<String> selected = quantities.keySet();
        for (var mutex : rules.mutexSets()) {
            if (mutex == null || mutex.stream().filter(selected::contains).limit(2).count() > 1)
                throw bad();
        }
        for (var dependency : rules.requires()) {
            if (dependency == null || dependency.requiresItemNos() == null) throw bad();
            if (selected.contains(dependency.optionItemNo())
                    && !selected.containsAll(dependency.requiresItemNos())) throw bad();
        }
    }

    private static void bundles(
            OrderLineInput line, boolean bundleDish, List<CatalogDependencyVO> dependencies) {
        var matches =
                dependencies.stream()
                        .filter(
                                d ->
                                        "BUNDLE".equals(d.configKind())
                                                && line.skuNo().equals(d.configNo()))
                        .toList();
        if (matches.isEmpty()) {
            if (bundleDish) throw conflict();
            if (!line.bundleChoices().isEmpty()) throw bad();
            return;
        }
        if (matches.size() != 1 || matches.getFirst().bundle() == null) throw conflict();
        BundleConfigVO bundle = matches.getFirst().bundle();
        if (!line.skuNo().equals(bundle.configNo()) || bundle.data() == null) throw conflict();
        var quantities = new HashMap<String, BigDecimal>();
        var totals = new HashMap<String, BigDecimal>();
        var groups = new HashSet<String>();
        for (var group : bundle.data().groups()) if (!groups.add(group.groupNo())) throw bad();
        for (var choice : line.bundleChoices()) {
            var candidates =
                    bundle.data().choices().stream()
                            .filter(c -> choice.choiceNo().equals(c.choiceNo()))
                            .toList();
            if (candidates.size() != 1
                    || !groups.contains(candidates.getFirst().groupNo())
                    || quantities.put(choice.choiceNo(), choice.qty()) != null) throw bad();
        }
        for (var choice : bundle.data().choices()) {
            if (!groups.contains(choice.groupNo())) throw bad();
            var qty = quantities.getOrDefault(choice.choiceNo(), BigDecimal.ZERO);
            bounds(qty, choice.minQty(), choice.maxQty());
            totals.merge(choice.groupNo(), qty, BigDecimal::add);
        }
        for (var group : bundle.data().groups()) {
            bounds(
                    totals.getOrDefault(group.groupNo(), BigDecimal.ZERO),
                    group.minQty(),
                    group.maxQty());
        }
    }

    private static void bounds(BigDecimal qty, BigDecimal min, BigDecimal max) {
        if (min == null || max == null || qty.compareTo(min) < 0 || qty.compareTo(max) > 0)
            throw bad();
    }

    private static BizException bad() {
        return BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }
}
